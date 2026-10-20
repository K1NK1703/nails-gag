package gag.nails.files.error;

import gag.nails.common.error.UserFacingException;

/**
 * Содержимое — не картинка JPEG, PNG или WebP, либо картинку не удалось прочитать. Формат определяется по первым
 * байтам: расширение имени и заявленный тип не учитываются.
 */
public final class UnsupportedFormatException extends UserFacingException {

    public UnsupportedFormatException() {
        super("files.unsupported_format");
    }
}
