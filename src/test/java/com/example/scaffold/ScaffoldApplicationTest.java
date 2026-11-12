package com.example.scaffold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Starts the whole application on PostgreSQL and drives the browser login journey.
 *
 * <p>The test user is inserted directly as a test fixture only. Real projects provision users
 * through their own approved route; the scaffold ships no built-in user or password.
 */
@SpringBootTest(properties = {
        "koiki.identity.login-attempt.source-hmac-key-id=scaffold-test",
        // Test-only key (Base64 of 32 bytes); real keys come from the deployment environment.
        "koiki.identity.login-attempt.source-hmac-key=c2NhZmZvbGQtdGVzdC1obWFjLWtleS0zMi1ieXRlcyE=",
        "server.servlet.session.cookie.secure=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(ScaffoldPostgreSqlTestConfiguration.class)
class ScaffoldApplicationTest {

    private static final String EMAIL = "scaffold-user@example.test";
    private static final String PASSWORD = "scaffold-test-password-0001";

    @Autowired private MockMvc mvc;
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
    void appliesFrameworkMigrations() {
        Integer tables = jdbc.sql("""
                select count(*) from information_schema.tables
                where table_name in ('koiki_user', 'koiki_session', 'koiki_audit_event')
                """).query(Integer.class).single();
        assertThat(tables).isEqualTo(3);
    }

    @Test
    void redirectsAnonymousHomeToLogin() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/login")).andExpect(status().isOk());
    }

    @Test
    void deniesRoutesWithoutAnApplicationChain() throws Exception {
        int status = mvc.perform(get("/unmapped")).andReturn().getResponse().getStatus();
        assertThat(status).isIn(401, 403);
    }

    @Test
    void rejectsLoginWithoutCsrfToken() throws Exception {
        mvc.perform(post("/login").param("username", EMAIL).param("password", PASSWORD))
                .andExpect(status().isForbidden());
    }

    @Test
    void logsInWithJdbcSessionAndLogsOut() throws Exception {
        UUID userId = createUser();

        MvcResult login = mvc.perform(post("/login").with(csrf())
                        .param("username", EMAIL).param("password", PASSWORD))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/"))
                .andReturn();
        Cookie session = Objects.requireNonNull(login.getResponse().getCookie("SESSION"));

        mvc.perform(get("/").cookie(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(userId.toString())));
        assertThat(sessionsOf(userId)).isEqualTo(1);

        mvc.perform(post("/logout").with(csrf()).cookie(session)).andExpect(status().is3xxRedirection());
        assertThat(sessionsOf(userId)).isZero();
        mvc.perform(get("/").cookie(session)).andExpect(status().is3xxRedirection());
    }

    @Test
    void rejectsWrongPassword() throws Exception {
        createUser();
        mvc.perform(post("/login").with(csrf()).param("username", EMAIL).param("password", "wrong-password-000001"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login?error"));
    }

    private int sessionsOf(UUID userId) {
        return jdbc.sql("select count(*) from koiki_session where principal_name = :name")
                .param("name", userId.toString()).query(Integer.class).single();
    }

    private UUID createUser() {
        UUID userId = UUID.randomUUID();
        jdbc.sql("""
                insert into koiki_user(user_id, email, canonical_email, status, version)
                values (:id, :email, :email, 'ACTIVE', 0)
                """).param("id", userId).param("email", EMAIL).update();
        jdbc.sql("insert into koiki_password_credential(user_id, encoded_password) values (:id, :password)")
                .param("id", userId).param("password", passwordEncoder.encode(PASSWORD)).update();
        return userId;
    }
}
