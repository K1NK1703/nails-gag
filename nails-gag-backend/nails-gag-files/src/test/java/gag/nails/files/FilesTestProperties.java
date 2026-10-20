package gag.nails.files;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.TestPropertySource;

/**
 * Настройки хранилища в тестах модуля. Одна аннотация на всех: одинаковые свойства дают тестам один кэшированный
 * контекст Spring и один контейнер PostgreSQL, в каком бы пакете модуля ни лежал тест.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@TestPropertySource(
        properties = {
            "nails-gag.files.max-file-bytes=" + FilesTestProperties.MAX_FILE_BYTES,
            "nails-gag.files.max-concurrent-processing=" + FilesTestProperties.PROCESSING_SLOTS,
            "nails-gag.files.processing-wait=200ms"
        })
public @interface FilesTestProperties {

    /** Лимит размера файла в тестах — 1 МиБ, чтобы проверка лимита не гоняла 15 МБ. */
    long MAX_FILE_BYTES = 1_048_576;

    int PROCESSING_SLOTS = 2;
}
