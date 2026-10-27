package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.app.portfolio.PortfolioImport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

/**
 * Импорт галереи есть только в профиле {@code local}: без него бина нет, и никакое свойство его не запустит. Те же
 * настройки, что у {@link PortfolioConcurrencyTest}, — контекст Spring и база у них общие.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE, properties = "nails-gag.booking.jobs.enabled=false")
@Import(PostgresTestcontainers.class)
class PortfolioImportProfileTest {

    @Autowired
    ApplicationContext context;

    @Test
    void importExistsOnlyInLocalProfile() {
        assertThat(context.getEnvironment().getActiveProfiles()).doesNotContain("local");
        assertThat(context.getBeanProvider(PortfolioImport.class).getIfAvailable())
                .isNull();
    }
}
