package gag.nails.common.paging;

import gag.nails.common.error.ValidationException;

/**
 * Страница списка. Отрицательный номер, размер вне диапазона или страница дальше {@link Integer#MAX_VALUE}
 * строк от начала — {@link ValidationException} с полем {@code page} или {@code size}.
 *
 * @param page номер страницы с нуля
 * @param size строк на странице, от 1 до {@link #MAX_SIZE}
 */
public record Paging(int page, int size) {

    public static final int MAX_SIZE = 200;

    public Paging {
        if (page < 0) {
            throw new ValidationException("page", "must not be negative");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new ValidationException("size", "must be between 1 and " + MAX_SIZE);
        }
        // Spring Data принимает смещение только в пределах int: дальше — сбой вместо ошибки запроса.
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new ValidationException("page", "is too large");
        }
    }

    /** Сколько строк списка идёт до этой страницы. */
    public long offset() {
        return (long) page * size;
    }
}
