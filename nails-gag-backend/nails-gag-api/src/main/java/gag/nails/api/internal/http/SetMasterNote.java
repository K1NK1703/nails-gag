package gag.nails.api.internal.http;

import jakarta.validation.constraints.NotNull;

/**
 * Тело {@code PUT /api/clients/{id}/note} и {@code PUT /api/bookings/{id}/note}: заметка мастера целиком. Длину
 * проверяет модуль: у записи — до 2000 символов ({@code 400 common.validation}), у карточки клиента ограничения нет.
 *
 * @param note новый текст; пустая строка стирает заметку
 */
public record SetMasterNote(@NotNull String note) {}
