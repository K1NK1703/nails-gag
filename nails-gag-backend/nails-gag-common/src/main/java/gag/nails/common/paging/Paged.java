package gag.nails.common.paging;

import java.util.List;
import java.util.function.Function;

/**
 * Страница результата: её строки, номер и размер из запроса и общее число строк списка.
 *
 * @param total строк во всём списке, а не на странице
 */
public record Paged<T>(List<T> items, int page, int size, long total) {

    public Paged {
        items = List.copyOf(items);
    }

    public static <T> Paged<T> of(List<T> items, Paging paging, long total) {
        return new Paged<>(items, paging.page(), paging.size(), total);
    }

    /** Те же номер, размер и общее число, строки преобразованы. */
    public <R> Paged<R> map(Function<? super T, ? extends R> mapper) {
        return new Paged<>(items.stream().<R>map(mapper).toList(), page, size, total);
    }
}
