package gag.nails.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.common.error.InternalException;
import jakarta.servlet.MultipartConfigElement;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * Пределы multipart сервлета сверяются с пределом модуля файлов при старте: расхождение, отключённый multipart или
 * незаданный предел модуля — приложение не стартует.
 */
class UploadLimitsGuardTest {

    private static final long MB = 1024 * 1024;

    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner().withUserConfiguration(UploadLimitsGuard.class);

    @Test
    void acceptsMatchingLimits() {
        multipart(15 * MB, 16 * MB)
                .withPropertyValues("nails-gag.files.max-file-bytes=15728640")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsFileLimitOtherThanStorageLimit() {
        assertStops(multipart(10 * MB, 16 * MB).withPropertyValues("nails-gag.files.max-file-bytes=15728640"));
        assertStops(multipart(15 * MB, 16 * MB).withPropertyValues("nails-gag.files.max-file-bytes=1000"));
    }

    @Test
    void rejectsRequestLimitNotAboveFileLimit() {
        assertStops(multipart(15 * MB, 15 * MB).withPropertyValues("nails-gag.files.max-file-bytes=15728640"));
        assertStops(multipart(15 * MB, -1).withPropertyValues("nails-gag.files.max-file-bytes=15728640"));
    }

    @Test
    void requiresStorageLimitAndMultipart() {
        assertStops(multipart(15 * MB, 16 * MB));
        assertStops(runner.withPropertyValues("nails-gag.files.max-file-bytes=15728640"));
    }

    /** Вне веб-приложения загружать некуда — проверки нет. */
    @Test
    void skippedOutsideWebApplication() {
        new ApplicationContextRunner()
                .withUserConfiguration(UploadLimitsGuard.class)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(UploadLimitsGuard.class));
    }

    private WebApplicationContextRunner multipart(long maxFileSize, long maxRequestSize) {
        return runner.withBean(
                MultipartConfigElement.class, () -> new MultipartConfigElement("", maxFileSize, maxRequestSize, 0));
    }

    private static void assertStops(WebApplicationContextRunner configured) {
        configured.run(context -> {
            assertThat(context).hasFailed();
            var chain = new ArrayList<Throwable>();
            for (var t = context.getStartupFailure(); t != null && !chain.contains(t); t = t.getCause()) {
                chain.add(t);
            }
            assertThat(chain).anyMatch(InternalException.class::isInstance);
        });
    }
}
