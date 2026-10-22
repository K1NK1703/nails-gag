package gag.nails.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.common.error.InternalException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Пределы multipart сверяются с пределом модуля файлов при старте: расхождение — приложение не стартует. */
class UploadLimitsGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(UploadLimitsGuard.class)
            .withPropertyValues(
                    "spring.servlet.multipart.max-file-size=15MB",
                    "spring.servlet.multipart.max-request-size=16MB",
                    "nails-gag.files.max-file-bytes=15728640");

    @Test
    void acceptsMatchingLimits() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsFileLimitOtherThanStorageLimit() {
        assertStopsTheApplication("spring.servlet.multipart.max-file-size=10MB");
        assertStopsTheApplication("nails-gag.files.max-file-bytes=1000");
    }

    @Test
    void rejectsRequestLimitNotAboveFileLimit() {
        assertStopsTheApplication("spring.servlet.multipart.max-request-size=15MB");
        assertStopsTheApplication("spring.servlet.multipart.max-request-size=-1");
    }

    private void assertStopsTheApplication(String property) {
        runner.withPropertyValues(property).run(context -> {
            assertThat(context).as(property).hasFailed();
            var chain = new ArrayList<Throwable>();
            for (var t = context.getStartupFailure(); t != null && !chain.contains(t); t = t.getCause()) {
                chain.add(t);
            }
            assertThat(List.copyOf(chain)).anyMatch(InternalException.class::isInstance);
        });
    }
}
