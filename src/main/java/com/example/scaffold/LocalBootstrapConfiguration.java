package com.example.scaffold;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.koikifw.audit.BusinessAuditRecorder;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

/** Explicit non-Web, loopback PostgreSQL bootstrap used only by tools/run-local.ps1. */
@Configuration(proxyBeanMethods = false)
@Profile("local-bootstrap")
@ConditionalOnNotWebApplication
@ConditionalOnProperty(name = "scaffold.bootstrap-admin.enabled", havingValue = "true")
class LocalBootstrapConfiguration {

    @Bean
    ApplicationRunner localAdminBootstrapRunner(JdbcClient jdbc, PasswordEncoder encoder,
            PlatformTransactionManager manager, BusinessAuditRecorder audit, Environment environment) {
        return args -> {
            String url = environment.getRequiredProperty("spring.datasource.url");
            if (!url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_-]+")) {
                throw new IllegalStateException("Local bootstrap requires a loopback PostgreSQL URL with an explicit port.");
            }
            var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String email = reader.readLine();
            String input = reader.readLine();
            if (email == null || input == null) {
                throw new IllegalArgumentException("Use tools/run-local.ps1 -Action CreateAdmin to supply credentials.");
            }
            char[] password = input.toCharArray();
            try {
                new LocalAdminProvisioner(jdbc, encoder, manager, audit).create(email, password);
                System.out.println("Initial local administrator created. Role: SCAFFOLD_ADMIN; permission: SCAFFOLD:ADMIN.");
            } finally {
                Arrays.fill(password, '\0');
            }
        };
    }
}
