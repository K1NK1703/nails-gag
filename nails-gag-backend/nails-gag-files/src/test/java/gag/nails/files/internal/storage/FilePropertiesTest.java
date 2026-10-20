package gag.nails.files.internal.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FilePropertiesTest {

    private final ApplicationContextRunner context =
            new ApplicationContextRunner().withUserConfiguration(FilesConfiguration.class);

    @Test
    void defaultsFollowSpecification() {
        context.withPropertyValues("nails-gag.files.root=/srv/files").run(started -> {
            var properties = started.getBean(FileProperties.class);
            assertThat(properties.root()).isEqualTo(Path.of("/srv/files"));
            assertThat(properties.maxFileBytes()).isEqualTo(15 * 1024 * 1024);
            assertThat(properties.maxPixels()).isEqualTo(50_000_000);
            assertThat(properties.maxConcurrentProcessing()).isEqualTo(2);
            assertThat(properties.processingWait()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.jobs().enabled()).isTrue();
            assertThat(properties.jobs().cleanupCron()).isEqualTo("0 30 3 * * *");
        });
    }

    @Test
    void rootIsRequired() {
        context.run(started -> assertThat(started).getFailure().hasStackTraceContaining("nails-gag.files.root"));
    }

    @Test
    void limitsMustBePositive() {
        var limits = Map.of(
                "max-file-bytes", "maxFileBytes",
                "max-pixels", "maxPixels",
                "max-concurrent-processing", "maxConcurrentProcessing");
        limits.forEach((property, field) -> context.withPropertyValues(
                        "nails-gag.files.root=/srv/files", "nails-gag.files." + property + "=0")
                .run(started -> assertThat(started)
                        .getFailure()
                        .as(property)
                        .hasStackTraceContaining("field '" + field + "': rejected value [0]")));
    }
}
