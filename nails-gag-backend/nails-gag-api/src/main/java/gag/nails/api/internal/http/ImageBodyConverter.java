package gag.nails.api.internal.http;

import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;

/**
 * Пишет {@link ImageBody} в ответ {@code image/jpeg}: копирует поток целиком и закрывает его. Только запись — телом
 * запроса картинка не бывает.
 */
final class ImageBodyConverter extends AbstractHttpMessageConverter<ImageBody> {

    ImageBodyConverter() {
        super(MediaType.IMAGE_JPEG);
    }

    @Override
    protected boolean supports(Class<?> type) {
        return ImageBody.class == type;
    }

    @Override
    public boolean canRead(Class<?> type, @Nullable MediaType mediaType) {
        return false;
    }

    @Override
    protected ImageBody readInternal(Class<? extends ImageBody> type, HttpInputMessage message) {
        throw new HttpMessageNotReadableException("an image is never a request body", message);
    }

    @Override
    protected Long getContentLength(ImageBody body, @Nullable MediaType contentType) {
        return body.length();
    }

    @Override
    protected void writeInternal(ImageBody body, HttpOutputMessage message) throws IOException {
        try (var content = body.content()) {
            content.transferTo(message.getBody());
        }
    }
}
