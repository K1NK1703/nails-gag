package gag.nails.analytics.model;

/**
 * Ключ метрики за период и сколько раз он засчитан.
 *
 * @param key ключ метрики ({@code /booking}, {@code hero}, {@code ya.ru}, …), как он хранится в счётчиках дня
 * @param count сумма счётчиков ключа за дни периода, больше нуля
 */
public record KeyCount(String key, long count) {}
