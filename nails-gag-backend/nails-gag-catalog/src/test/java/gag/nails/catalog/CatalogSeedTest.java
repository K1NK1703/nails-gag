package gag.nails.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.testsupport.ModuleTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@ModuleTest
class CatalogSeedTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void landingPriceListIsSeeded() {
        var titles = jdbc.sql("select title from catalog.service where show_on_landing order by sort_order")
                .query(String.class)
                .list();

        assertThat(titles).containsExactly("Маникюр", "Френч", "Втирка / дизайн");
    }
}
