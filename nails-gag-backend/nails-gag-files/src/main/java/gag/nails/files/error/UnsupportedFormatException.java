package gag.nails.files.error;

import gag.nails.common.error.UserFacingException;

/**
 * Содержимое — не картинка JPEG, PNG или WebP, в нём нет данных картинки, либо картинку не удалось прочитать (в т. ч.
 * однотонный WebP без потерь со сжатием сильнее 2048:1 — его TwelveMonkeys отвергает). Формат определяется по первым
 * байтам: расширение имени и заявленный тип не учитываются.
 */
public final class UnsupportedFormatException extends UserFacingException {

    public UnsupportedFormatException() {
        super("files.unsupported_format");
    }
}
