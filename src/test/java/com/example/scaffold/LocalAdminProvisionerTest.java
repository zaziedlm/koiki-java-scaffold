package com.example.scaffold;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.koikifw.audit.BusinessAuditRecorder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "koiki.identity.login-attempt.source-hmac-key-id=bootstrap-test",
        "koiki.identity.login-attempt.source-hmac-key=c2NhZmZvbGQtdGVzdC1obWFjLWtleS0zMi1ieXRlcyE=",
        "server.servlet.session.cookie.secure=false"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(ScaffoldPostgreSqlTestConfiguration.class)
class LocalAdminProvisionerTest {

    private static final String PASSWORD = "bootstrap-test-password-0001";
    @Autowired private JdbcClient jdbc;
    @Autowired private PasswordEncoder encoder;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private BusinessAuditRecorder audit;
    @Autowired private MockMvc mvc;
    @Autowired private ApplicationContext context;

    @AfterEach
    void clean() {
        jdbc.sql("delete from koiki_session_attributes").update();
        jdbc.sql("delete from koiki_session").update();
        jdbc.sql("delete from koiki_login_attempt").update();
        jdbc.sql("delete from koiki_user_role").update();
        jdbc.sql("delete from koiki_role_permission").update();
        jdbc.sql("delete from koiki_password_credential").update();
        jdbc.sql("delete from koiki_user").update();
        jdbc.sql("delete from koiki_role").update();
        jdbc.sql("delete from koiki_permission").update();
        jdbc.sql("delete from koiki_audit_event").update();
    }

    @Test
    void initialAdminCanLogInAndHasApplicationAdminPermission() throws Exception {
        assertThat(context.containsBean("localAdminBootstrapRunner")).isFalse();
        create(new LocalAdminProvisioner(jdbc, encoder, manager, audit), "  Admin@example.test  ", PASSWORD);
        assertThat(jdbc.sql("select canonical_email from koiki_user").query(String.class).single())
                .isEqualTo("admin@example.test");
        String encoded = jdbc.sql("select encoded_password from koiki_password_credential")
                .query(String.class).single();
        assertThat(encoded).isNotEqualTo(PASSWORD);
        assertThat(encoder.matches(PASSWORD, encoded)).isTrue();
        assertThat(jdbc.sql("select count(*) from koiki_audit_event where action = 'CREATE_INITIAL_ADMIN'")
                .query(Long.class).single()).isEqualTo(1);
        var login = mvc.perform(post("/login").with(csrf()).param("username", "admin@example.test")
                .param("password", PASSWORD)).andExpect(status().is3xxRedirection()).andReturn();
        var session = Objects.requireNonNull(login.getResponse().getCookie("SESSION"));
        mvc.perform(get("/").cookie(session)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SCAFFOLD:ADMIN")));
    }

    @Test
    void repeatRegistrationRefusesToChangeTheExistingAccount() {
        var provisioner = new LocalAdminProvisioner(jdbc, encoder, manager, audit);
        create(provisioner, "admin@example.test", PASSWORD);
        String encoded = jdbc.sql("select encoded_password from koiki_password_credential")
                .query(String.class).single();
        assertThatThrownBy(() -> create(provisioner, "other@example.test", "another-test-password-0002"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already has users");
        assertThat(jdbc.sql("select count(*) from koiki_user").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select encoded_password from koiki_password_credential")
                .query(String.class).single()).isEqualTo(encoded);
    }

    @Test
    void invalidPasswordCreatesNothing() {
        var provisioner = new LocalAdminProvisioner(jdbc, encoder, manager, audit);
        assertThatThrownBy(() -> create(provisioner, "admin@example.test", "short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertEmpty();
    }

    @Test
    void auditFailureRollsBackAllProvisionedState() {
        var provisioner = new LocalAdminProvisioner(jdbc, encoder, manager, event -> {
            throw new IllegalStateException("injected audit failure");
        });
        assertThatThrownBy(() -> create(provisioner, "admin@example.test", PASSWORD))
                .isInstanceOf(IllegalStateException.class).hasMessage("injected audit failure");
        assertEmpty();
    }

    private void assertEmpty() {
        for (String table : new String[] {"koiki_user", "koiki_password_credential", "koiki_role",
                "koiki_permission", "koiki_user_role", "koiki_role_permission", "koiki_audit_event"}) {
            assertThat(jdbc.sql("select count(*) from " + table).query(Long.class).single()).isZero();
        }
    }

    private static void create(LocalAdminProvisioner provisioner, String email, String password) {
        char[] characters = password.toCharArray();
        try {
            provisioner.create(email, characters);
        } finally {
            Arrays.fill(characters, '\0');
        }
    }
}
