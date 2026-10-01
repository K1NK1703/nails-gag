package gag.nails.clients;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.testsupport.ModuleTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@ModuleTest
class ClientsSeedTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void currentDocumentVersionsAreSeeded() {
        var versions = jdbc.sql("select kind || ':' || version from clients.document_version order by kind")
                .query(String.class)
                .list();

        assertThat(versions).containsExactly("OFFER:2026-09-25", "POLICY:2026-09-25");
    }
}
