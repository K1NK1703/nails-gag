package gag.nails.api.internal;

import gag.nails.common.error.InternalException;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Пределы загрузки сходятся при старте (спецификация файлов и портфолио, §6.1). Предел файла multipart сервлета
 * ({@code spring.servlet.multipart.max-file-size}) равен {@code nails-gag.files.max-file-bytes} — иначе один и тот же
 * файл отвергал бы то Tomcat, то модуль файлов, с разным {@code maxBytes} в ответе; предел запроса задан и больше
 * него — в запросе кроме файла есть заголовки формы, а ранний отказ по {@code Content-Length} опирается на этот
 * предел. Пределы сервлета берутся из {@link MultipartConfigElement}, который построил Spring Boot, — без своих
 * умолчаний; предел модуля файлов должен быть задан явно: его настройки внутренние, и умолчание модуля здесь не
 * повторяется. Нет multipart, свойства или пределы расходятся — {@link InternalException}, приложение не стартует.
 * Проверка — только в веб-приложении на сервлетах: без него загружать некуда и multipart не настраивается.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class UploadLimitsGuard implements InitializingBean {

    static final String STORAGE_MAX_FILE_BYTES = "nails-gag.files.max-file-bytes";

    private final Environment environment;
    private final ObjectProvider<MultipartConfigElement> multipart;

    UploadLimitsGuard(Environment environment, ObjectProvider<MultipartConfigElement> multipart) {
        this.environment = environment;
        this.multipart = multipart;
    }

    @Override
    public void afterPropertiesSet() {
        var config = multipart.getIfAvailable();
        if (config == null) {
            throw new InternalException("multipart is disabled: photo uploads need spring.servlet.multipart");
        }
        var storageMax = environment.getProperty(STORAGE_MAX_FILE_BYTES, Long.class);
        if (storageMax == null) {
            throw new InternalException(
                    STORAGE_MAX_FILE_BYTES + " must be set: multipart limits are checked against it");
        }
        if (config.getMaxFileSize() != storageMax) {
            throw new InternalException("spring.servlet.multipart.max-file-size (" + config.getMaxFileSize()
                    + " bytes) must equal " + STORAGE_MAX_FILE_BYTES + " (" + storageMax + ")");
        }
        if (config.getMaxRequestSize() <= config.getMaxFileSize()) {
            throw new InternalException(
                    "spring.servlet.multipart.max-request-size must be set and greater than max-file-size");
        }
    }
}
