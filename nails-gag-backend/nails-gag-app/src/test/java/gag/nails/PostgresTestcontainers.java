package gag.nails;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Копия класса из test-support: app не может зависеть от test-support (см. build.gradle.kts). */
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestcontainers {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17-alpine");
    }
}
