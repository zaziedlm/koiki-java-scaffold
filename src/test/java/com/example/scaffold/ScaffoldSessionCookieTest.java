package com.example.scaffold;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.web.http.CookieSerializer;

/** Exercises Boot's embedded-server cookie configuration through real HTTP requests. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "koiki.identity.login-attempt.source-hmac-key-id=scaffold-cookie-test",
        "koiki.identity.login-attempt.source-hmac-key=c2NhZmZvbGQtdGVzdC1obWFjLWtleS0zMi1ieXRlcyE=",
        "server.servlet.context-path=/scaffold",
        "server.servlet.session.cookie.name=SCAFFOLDSESSION",
        "server.servlet.session.cookie.path=/scaffold"})
@Import(ScaffoldPostgreSqlTestConfiguration.class)
class ScaffoldSessionCookieTest {

    private static final String EMAIL = "cookie-user@example.test";
    private static final String PASSWORD = "scaffold-cookie-test-password-0001";
    private static final String COOKIE = "SCAFFOLDSESSION";

    @LocalServerPort private int port;
    @Autowired private ApplicationContext context;
    @Autowired private JdbcClient jdbc;
    @Autowired private PasswordEncoder passwordEncoder;

    @AfterEach
    void clean() {
        jdbc.sql("delete from koiki_session_attributes").update();
        jdbc.sql("delete from koiki_session").update();
        jdbc.sql("delete from koiki_password_credential").update();
        jdbc.sql("delete from koiki_login_attempt").update();
        jdbc.sql("delete from koiki_user").update();
    }

    @Test
    void bootCookieWorksForLoginAndLogoutWithConfiguredNameAndPath() throws Exception {
        assertThat(context.getBeansOfType(CookieSerializer.class)).containsOnlyKeys("cookieSerializer");
        UUID userId = UUID.randomUUID();
        jdbc.sql("""
                insert into koiki_user(user_id, email, canonical_email, status, version)
                values (:id, :email, :email, 'ACTIVE', 0)
                """).param("id", userId).param("email", EMAIL).update();
        jdbc.sql("insert into koiki_password_credential(user_id, encoded_password) values (:id, :password)")
                .param("id", userId).param("password", passwordEncoder.encode(PASSWORD)).update();

        // Deliberately send Cookie headers ourselves: this loopback HTTP test checks the Secure
        // attribute without pretending to test a browser's HTTPS-only cookie policy.
        try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            var loginPage = client.send(request("/login").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(loginPage.statusCode()).isEqualTo(200);
            String initialCookie = cookiePair(cookieHeader(loginPage));
            String form = "username=" + encode(EMAIL) + "&password=" + encode(PASSWORD)
                    + "&_csrf=" + encode(csrf(loginPage.body()));
            var login = client.send(request("/login").header("Cookie", initialCookie)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(login.statusCode()).isEqualTo(302);
            assertThat(URI.create(login.headers().firstValue("Location").orElseThrow()).getPath())
                    .isEqualTo("/scaffold/");
            String issued = cookieHeader(login);
            assertAttributes(issued);
            String session = cookiePair(issued);
            assertThat(sessionsOf(userId)).isEqualTo(1);

            var home = client.send(request("/").header("Cookie", session).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(home.statusCode()).isEqualTo(200);
            assertThat(home.body()).contains(userId.toString());
            var logout = client.send(request("/logout").header("Cookie", session)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + encode(csrf(home.body())))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(logout.statusCode()).isEqualTo(302);
            var deleted = logout.headers().allValues("Set-Cookie").stream()
                    .filter(value -> value.startsWith(COOKIE + "=")).toList();
            assertThat(deleted).isNotEmpty();
            for (String header : deleted) {
                assertAttributes(header);
                assertThat(HttpCookie.parse(header).getFirst().getMaxAge()).isZero();
                assertThat(HttpCookie.parse(header).getFirst().getValue()).isEmpty();
            }
            assertThat(sessionsOf(userId)).isZero();
            var afterLogout = client.send(request("/").header("Cookie", session).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(afterLogout.statusCode()).isEqualTo(302);
            assertThat(URI.create(afterLogout.headers().firstValue("Location").orElseThrow()).getPath())
                    .isEqualTo("/scaffold/login");
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/scaffold" + path));
    }

    private static void assertAttributes(String header) {
        HttpCookie cookie = HttpCookie.parse(header).getFirst();
        assertThat(cookie.getName()).isEqualTo(COOKIE);
        assertThat(cookie.getPath()).isEqualTo("/scaffold");
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(header).contains("SameSite=Lax");
    }

    private static String cookieHeader(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith(COOKIE + "=")).findFirst().orElseThrow();
    }

    private static String cookiePair(String header) {
        return header.substring(0, header.indexOf(';'));
    }

    private static String csrf(String html) {
        var inputs = Pattern.compile("<input\\b[^>]*>").matcher(html);
        while (inputs.find()) {
            String input = inputs.group();
            if (input.contains("name=\"_csrf\"")) {
                var value = Pattern.compile("\\bvalue=\"([^\"]+)\"").matcher(input);
                if (value.find()) {
                    return value.group(1);
                }
            }
        }
        throw new IllegalStateException("CSRF hidden input not found");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private int sessionsOf(UUID userId) {
        return jdbc.sql("select count(*) from koiki_session where principal_name = :name")
                .param("name", userId.toString()).query(Integer.class).single();
    }
}
