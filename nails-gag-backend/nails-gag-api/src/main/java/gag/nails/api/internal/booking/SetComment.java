package gag.nails.api.internal.booking;

import org.jspecify.annotations.Nullable;

/**
 * Тело {@code PUT /api/bookings/{id}/comment}: комментарий клиента к записи целиком.
 *
 * @param comment новый текст; {@code null} или пустая строка стирает комментарий. Длину (до 500 символов) проверяет
 *     модуль после проверки прав — {@code 400 common.validation} с полем {@code comment}
 */
record SetComment(@Nullable String comment) {}
