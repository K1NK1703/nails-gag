package gag.nails.files.internal.storage;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code nails-gag.files.*}; неверные значения не дают приложению стартовать.
 *
 * @param root корень хранилища; создаётся при первой загрузке. В Docker — том, в {@code local} и тестах — временный
 *     каталог
 * @param maxFileBytes наибольший размер загружаемого файла
 * @param maxPixels наибольшее число пикселей картинки по заголовку
 * @param maxConcurrentProcessing сколько картинок обрабатывается одновременно; обработка съедает память и процессор
 * @param processingWait сколько загрузка ждёт места обработки, прежде чем ответить «занято»
 */
@Validated
@ConfigurationProperties("nails-gag.files")
record FileProperties(
        @NotNull Path root,
        @DefaultValue("15728640") @Positive long maxFileBytes,
        @DefaultValue("50000000") @Positive long maxPixels,
        @DefaultValue("2") @Positive int maxConcurrentProcessing,
        @DefaultValue("10s") @NotNull Duration processingWait) {}
