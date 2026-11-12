package com.example.scaffold;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** PostgreSQL for application tests; keep the image in line with compose.yaml. */
@TestConfiguration(proxyBeanMethods = false)
public class ScaffoldPostgreSqlTestConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgreSqlContainer() {
        return new PostgreSQLContainer("postgres:17-alpine");
    }
}
