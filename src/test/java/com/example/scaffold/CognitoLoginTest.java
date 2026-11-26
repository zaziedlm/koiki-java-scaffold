package com.example.scaffold;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.koikifw.identity.FrameworkUserId;
import org.koikifw.identity.IdentityQuery;
import org.koikifw.identity.UserSessionInvalidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "SCAFFOLD_COGNITO_ISSUER=https://issuer.example.test/pool",
        "SCAFFOLD_COGNITO_DOMAIN=https://login.example.test",
        "SCAFFOLD_COGNITO_PUBLIC_ORIGIN=http://localhost:18080",
        "SCAFFOLD_COGNITO_CLIENT_ID=scaffold-client",
        "SCAFFOLD_COGNITO_CLIENT_SECRET=test-only",
        "server.servlet.session.cookie.secure=false"})
@ActiveProfiles("cognito")
@Import({ScaffoldPostgreSqlTestConfiguration.class, CognitoLoginTest.Provider.class})
class CognitoLoginTest {

    @LocalServerPort private int port;
    @Autowired private JdbcClient jdbc;
    @Autowired private IdentityQuery identities;
    @Autowired private UserSessionInvalidator invalidator;

    @Test
    void linkedUserGetsJdbcSessionAndCognitoLogout() throws Exception {
        FrameworkUserId id = link("linked");
        try (var client = HttpClient.newHttpClient()) {
            assertThat(location(get(client, "/", ""))).endsWith(CognitoSettings.START);
            assertThat(get(client, "/login", "").statusCode()).isEqualTo(401);

            var firstLogin = login(client, "linked", true);
            assertThat(location(firstLogin)).isEqualTo("http://localhost:18080/");
            String cookie = cookie(firstLogin);
            var home = get(client, "/", cookie);
            assertThat(home.statusCode()).isEqualTo(200);
            assertThat(home.body()).contains(id.toString(), "SCAFFOLD:ADMIN");
            assertThat(sessions(id)).isEqualTo(1);

            String csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"")
                    .matcher(home.body()).results().findFirst().orElseThrow().group(1);
            var logout = client.send(HttpRequest.newBuilder(url("/logout")).header("Cookie", cookie)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("_csrf=" + csrf)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(location(logout)).isEqualTo(
                    "https://login.example.test/logout?client_id=scaffold-client&logout_uri=http%3A%2F%2Flocalhost%3A18080%2Fsigned-out");
            assertThat(sessions(id)).isZero();
            assertThat(location(get(client, "/", cookie))).endsWith(CognitoSettings.START);
            assertThat(get(client, CognitoSettings.SIGNED_OUT, "").body()).contains("ログアウトしました");

            var secondLogin = login(client, "linked", true);
            assertThat(sessions(id)).isEqualTo(1);
            invalidator.invalidateAll(id);
            assertThat(location(get(client, "/", cookie(secondLogin)))).endsWith(CognitoSettings.START);
        }
    }

    @Test
    void rejectsUnlinkedAndDisabledIdentitiesAndInvalidOidcResponses() throws Exception {
        FrameworkUserId disabled = link("disabled");
        jdbc.sql("update koiki_user set status = 'DISABLED' where user_id = :id")
                .param("id", disabled.value()).update();
        link("wrong-nonce");
        try (var client = HttpClient.newHttpClient()) {
            assertThat(location(login(client, "unlinked", true))).endsWith(CognitoSettings.LOGIN_FAILED);
            assertThat(location(login(client, "disabled", true))).endsWith(CognitoSettings.LOGIN_FAILED);
            assertThat(location(login(client, "wrong-nonce", true))).endsWith(CognitoSettings.LOGIN_FAILED);
            assertThat(location(login(client, "unlinked", false))).endsWith(CognitoSettings.LOGIN_FAILED);
            assertThat(jdbc.sql("select count(*) from koiki_session where principal_name is not null")
                    .query(Long.class).single()).isZero();
        }
    }

    private HttpResponse<String> login(HttpClient client, String code, boolean correctState) throws Exception {
        var authorization = get(client, CognitoSettings.START, "");
        var query = UriComponentsBuilder.fromUriString(location(authorization)).build().getQueryParams();
        assertThat(query.getFirst("redirect_uri"))
                .isIn("http://localhost:18080" + CognitoSettings.CALLBACK,
                        "http%3A%2F%2Flocalhost%3A18080%2Flogin%2Foauth2%2Fcode%2Fcognito");
        assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(query.getFirst("code_challenge")).isNotBlank();
        assertThat(query.getFirst("nonce")).isNotBlank();
        String state = correctState ? query.getFirst("state") : "wrong-state";
        var callback = get(client, CognitoSettings.CALLBACK + "?code=" + code + "&state=" + state,
                cookie(authorization));
        if (URI.create(location(callback)).getPath().equals("/")) {
            assertThat(cookie(callback)).isNotEqualTo(cookie(authorization));
        }
        return callback;
    }

    private FrameworkUserId link(String subject) {
        UUID user = UUID.randomUUID();
        UUID role = UUID.randomUUID();
        String email = subject + "@example.test";
        jdbc.sql("""
                insert into koiki_user (user_id, email, canonical_email, status, version)
                values (:id, :email, :email, 'ACTIVE', 0)
                """).param("id", user).param("email", email).update();
        jdbc.sql("insert into koiki_role (role_id, role_code) values (:id, :code)")
                .param("id", role).param("code", "TEST_" + subject.toUpperCase(Locale.ROOT).replace('-', '_')).update();
        jdbc.sql("""
                insert into koiki_permission (permission_id, permission_code)
                values (:id, 'SCAFFOLD:ADMIN') on conflict do nothing
                """).param("id", UUID.randomUUID()).update();
        jdbc.sql("""
                insert into koiki_role_permission (role_id, permission_id)
                select :role, permission_id from koiki_permission where permission_code = 'SCAFFOLD:ADMIN'
                """).param("role", role).update();
        jdbc.sql("insert into koiki_user_role (user_id, role_id) values (:user, :role)")
                .param("user", user).param("role", role).update();
        var id = FrameworkUserId.parse(user.toString());
        new CognitoIdentityLinks(jdbc, identities).link("https://issuer.example.test/pool", subject, id);
        return id;
    }

    private int sessions(FrameworkUserId id) {
        return jdbc.sql("select count(*) from koiki_session where principal_name = :id")
                .param("id", id.toString()).query(Integer.class).single();
    }

    private HttpResponse<String> get(HttpClient client, String path, String cookie) throws Exception {
        var request = HttpRequest.newBuilder(url(path));
        if (!cookie.isEmpty()) {
            request.header("Cookie", cookie);
        }
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String location(HttpResponse<?> response) {
        assertThat(response.statusCode()).isEqualTo(302);
        return response.headers().firstValue("Location").orElseThrow();
    }

    private static String cookie(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("SESSION="))
                .findFirst().orElseThrow().split(";", 2)[0];
    }

    // Substitute only the remote token exchange and decoding. The application still handles
    // the actual callback, state and nonce checks, principal conversion and JDBC session lifecycle.
    @TestConfiguration(proxyBeanMethods = false)
    static class Provider {
        private final Map<String, Jwt> idTokens = new ConcurrentHashMap<>();

        @Bean
        OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenExchange() {
            return request -> {
                var exchange = request.getAuthorizationExchange();
                String subject = Objects.requireNonNull(exchange.getAuthorizationResponse().getCode());
                Object nonce = Objects.requireNonNull(exchange.getAuthorizationRequest().getAdditionalParameters().get("nonce"));
                String token = UUID.randomUUID().toString();
                idTokens.put(token, Jwt.withTokenValue(token).header("alg", "RS256").subject(subject)
                        .issuer("https://issuer.example.test/pool").audience(List.of("scaffold-client"))
                        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                        .claim("nonce", "wrong-nonce".equals(subject) ? "invalid" : nonce).build());
                return OAuth2AccessTokenResponse.withToken("test-access-token")
                        .tokenType(OAuth2AccessToken.TokenType.BEARER).expiresIn(300)
                        .additionalParameters(Map.of("id_token", token)).build();
            };
        }

        @Bean
        JwtDecoderFactory<ClientRegistration> testDecoder() {
            return registration -> token -> Objects.requireNonNull(idTokens.remove(token));
        }
    }
}
