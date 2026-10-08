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
    void scheduleChangelogCreatedExtensionAndExclusionConstraint() {
        var applied = jdbc.sql("select count(*) from databasechangelog where id = 'schedule-0001-btree-gist'")
                .query(Integer.class)
                .single();
        var extension = jdbc.sql("select count(*) from pg_extension where extname = 'btree_gist'")
                .query(Integer.class)
                .single();
        var constraint = jdbc.sql("select count(*) from pg_constraint where conname = 'slot_no_overlap'")
                .query(Integer.class)
                .single();

        assertThat(applied).isEqualTo(1);
        assertThat(extension).isEqualTo(1);
        assertThat(constraint).isEqualTo(1);
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

    @Test
    void clientsSchemaIsMigrated() {
        var versions = jdbc.sql("select count(*) from clients.document_version")
                .query(Integer.class)
                .single();

        assertThat(versions).isEqualTo(2);
    }

    @Test
    void clientsIdentitySupportIsMigrated() {
        var contactPhone = jdbc.sql("""
                        select count(*) from information_schema.columns
                        where table_schema = 'clients' and table_name = 'client' and column_name = 'contact_phone'
                        """).query(Integer.class).single();

        assertThat(contactPhone).isEqualTo(1);
    }

    @Test
    void bookingSchemaIsMigrated() {
        var settings = jdbc.sql("select count(*) from booking.settings")
                .query(Integer.class)
                .single();

        assertThat(settings).isEqualTo(1);
    }

    @Test
    void identitySchemaIsMigrated() {
        var builtInRoles = jdbc.sql("select count(*) from identity.role where built_in")
                .query(Integer.class)
                .single();

        assertThat(builtInRoles).isEqualTo(2);
    }
}
