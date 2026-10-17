package gag.nails.api.internal.http;

import gag.nails.common.paging.Paging;

/**
 * Значения по умолчанию параметров {@code page} и {@code size} постраничных адресов (спецификация API, §4.4) — строки
 * для {@code @RequestParam(defaultValue = …)}. Сами номер и размер проверяет {@link Paging}: номер не меньше нуля,
 * размер от 1 до {@link Paging#MAX_SIZE}, иначе {@code 400 common.validation}.
 */
public final class PageDefaults {

    /** Номер страницы по умолчанию — первая. */
    public static final String FIRST_PAGE = "0";

    /** Размер страницы по умолчанию; не больше {@link Paging#MAX_SIZE}. */
    public static final String SIZE = "50";

    private PageDefaults() {}
}
