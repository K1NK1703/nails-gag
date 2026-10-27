package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.app.portfolio.PortfolioImport;
import gag.nails.common.error.InternalException;
import gag.nails.common.paging.Paging;
import gag.nails.portfolio.PortfolioQueries;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Импорт ({@link PortfolioImport}) на базе, где никто не входил: аккаунта с правом записи в «Портфолио» нет. Свой
 * контекст и своя база (набор свойств отличается от {@link PortfolioImportTest}), поэтому в этом классе никто не входит.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.NONE,
        properties = {
            "nails-gag.booking.jobs.enabled=false",
            "nails-gag.identity.jobs.enabled=false",
            "nails-gag.files.jobs.enabled=false"
        })
@ActiveProfiles("local")
@Import(PostgresTestcontainers.class)
class PortfolioImportWithoutWriterTest {

    @Autowired
    PortfolioImport importer;

    @Autowired
    PortfolioQueries works;

    @TempDir
    Path directory;

    @Test
    void importWithoutPortfolioWriterIsRefused() throws IOException {
        Files.write(directory.resolve("photo.png"), Pictures.png(64, 48));
        var list = directory.resolve("gallery.json");
        Files.writeString(list, """
                [{ "file": "photo.png", "alt": "Импорт без аккаунта", "caption": null }]
                """);

        assertThatThrownBy(() -> importer.importFrom(list))
                .isInstanceOf(InternalException.class)
                .hasMessageContaining("needs an account with write access to Portfolio");
        assertThat(works.published(new Paging(0, Paging.MAX_SIZE)).total()).isZero();
    }

    @Test
    void emptySourceDoesNothing() {
        // Список не задан (умолчание профиля local): запуск приложения импорт не трогает, даже без аккаунта.
        assertThatCode(() -> importer.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
        assertThat(works.published(new Paging(0, Paging.MAX_SIZE)).total()).isZero();
    }
}
