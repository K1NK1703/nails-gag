package gag.nails.api.internal.http;

import gag.nails.files.model.FileVariant;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Отдача картинок из хранилища файлов (спецификация файлов и портфолио, §6.2–6.3): тело — поток файла на диске,
 * {@code Content-Type} и {@code Content-Length} — из варианта, {@code Cache-Control} — по видимости картинки.
 */
public final class ImageResponses {

    private static final Logger log = LoggerFactory.getLogger(ImageResponses.class);

    private static final String CLOSE_CALLBACK = ImageResponses.class.getName() + ".close.";

    private ImageResponses() {}

    /**
     * Ответ {@code 200} с вариантом картинки. Поток записывает в ответ и закрывает конвертер Spring MVC; если ответ так
     * и не запишется (отказ по {@code Accept}, ошибка до записи), поток закроется по завершении запроса — файл на диске
     * не останется открытым. Вызывать в потоке обработки запроса.
     */
    public static ResponseEntity<InputStreamResource> of(FileVariant variant, CacheControl cache) {
        var content = variant.content();
        RequestContextHolder.currentRequestAttributes()
                .registerDestructionCallback(
                        CLOSE_CALLBACK + System.identityHashCode(content),
                        () -> closeQuietly(content),
                        RequestAttributes.SCOPE_REQUEST);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(variant.contentType()))
                .contentLength(variant.length())
                .cacheControl(cache)
                .body(new InputStreamResource(content));
    }

    /**
     * Картинки нет или её нельзя показать: {@code 404 common.not_found} всем, в том числе сотруднику, — «нет» и
     * «нельзя» не различаются.
     */
    public static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    /** Повторное закрытие уже закрытого конвертером потока ничего не делает. */
    private static void closeQuietly(InputStream content) {
        try {
            content.close();
        } catch (IOException e) {
            log.debug("image stream close failed: {}", e.toString());
        }
    }
}
