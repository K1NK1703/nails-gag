package gag.nails.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Подключается в тестах через {@code @Import(PostgresTestcontainers.class)}. */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainers {

    public static final String IMAGE = "postgres:17-alpine";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(IMAGE);
    }
}
