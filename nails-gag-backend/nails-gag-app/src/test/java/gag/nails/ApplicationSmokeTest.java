package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainers.class)
class ApplicationSmokeTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Clock clock;

    @Test
    void healthIsUp() {
        var body = RestClient.create("http://localhost:" + port)
                .get()
                .uri("/actuator/health")
                .retrieve()
                .body(String.class);

        assertThat(body).contains("\"status\":\"UP\"");
    }

    @Test
    void liquibaseAppliedAppChangelog() {
        var applied = jdbc.sql("select count(*) from databasechangelog where id = 'app-0001-btree-gist'")
                .query(Integer.class)
                .single();
        var extension = jdbc.sql("select count(*) from pg_extension where extname = 'btree_gist'")
                .query(Integer.class)
                .single();

        assertThat(applied).isEqualTo(1);
        assertThat(extension).isEqualTo(1);
    }

    @Test
    void clockRunsInSalonTimeZone() {
        assertThat(clock.getZone().getId()).isEqualTo("Europe/Moscow");
    }

    @Test
    void catalogIsSeeded() {
        var services = jdbc.sql("select count(*) from catalog.service")
                .query(Integer.class)
                .single();

        assertThat(services).isEqualTo(3);
    }
}
