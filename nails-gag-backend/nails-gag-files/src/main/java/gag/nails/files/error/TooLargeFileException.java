package gag.nails.files.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;

/** Файл больше допустимого размера в байтах. */
public final class TooLargeFileException extends UserFacingException {

    public TooLargeFileException(long maxBytes) {
        super("files.too_large_file", Map.of("maxBytes", maxBytes));
    }
}
