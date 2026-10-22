package gag.nails.api.internal;

import gag.nails.common.error.InternalException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * Пределы загрузки сходятся при старте (спецификация файлов и портфолио, §6.1): {@code spring.servlet.multipart}
 * {@code .max-file-size} равен {@code nails-gag.files.max-file-bytes} — иначе один и тот же файл отвергал бы то
 * Tomcat, то модуль файлов, с разным {@code maxBytes} в ответе; {@code max-request-size} задан и больше него — в
 * запросе кроме файла есть заголовки формы, а ранний отказ по {@code Content-Length} опирается на этот предел.
 * Нарушение — {@link InternalException}, приложение не стартует. Умолчания — как у Spring Boot и модуля файлов.
 */
@Component
class UploadLimitsGuard implements InitializingBean {

    static final String MAX_FILE_SIZE = "spring.servlet.multipart.max-file-size";
    static final String MAX_REQUEST_SIZE = "spring.servlet.multipart.max-request-size";
    static final String STORAGE_MAX_FILE_BYTES = "nails-gag.files.max-file-bytes";

    /** Умолчания Spring Boot для multipart и модуля файлов. */
    private static final DataSize DEFAULT_MAX_FILE_SIZE = DataSize.ofMegabytes(1);

    private static final DataSize DEFAULT_MAX_REQUEST_SIZE = DataSize.ofMegabytes(10);
    private static final long DEFAULT_STORAGE_MAX_FILE_BYTES = 15_728_640L;

    private final Environment environment;

    UploadLimitsGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        var binder = Binder.get(environment);
        var maxFileSize = binder.bind(MAX_FILE_SIZE, DataSize.class)
                .orElse(DEFAULT_MAX_FILE_SIZE)
                .toBytes();
        var maxRequestSize = binder.bind(MAX_REQUEST_SIZE, DataSize.class)
                .orElse(DEFAULT_MAX_REQUEST_SIZE)
                .toBytes();
        var storageMax = binder.bind(STORAGE_MAX_FILE_BYTES, Long.class).orElse(DEFAULT_STORAGE_MAX_FILE_BYTES);
        if (maxFileSize != storageMax) {
            throw new InternalException(MAX_FILE_SIZE + " (" + maxFileSize + " bytes) must equal "
                    + STORAGE_MAX_FILE_BYTES + " (" + storageMax + ")");
        }
        if (maxRequestSize <= maxFileSize) {
            throw new InternalException(MAX_REQUEST_SIZE + " must be set and greater than " + MAX_FILE_SIZE);
        }
    }
}
