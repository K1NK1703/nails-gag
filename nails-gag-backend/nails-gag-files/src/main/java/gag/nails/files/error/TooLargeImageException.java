package gag.nails.files.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;

/** В картинке больше допустимого числа пикселей; проверяется по заголовку, до распаковки. */
public final class TooLargeImageException extends UserFacingException {

    public TooLargeImageException(long maxPixels) {
        super("files.too_large_image", Map.of("maxPixels", maxPixels));
    }
}
