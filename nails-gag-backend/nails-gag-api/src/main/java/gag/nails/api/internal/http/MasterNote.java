package gag.nails.api.internal.http;

import org.jspecify.annotations.Nullable;

/**
 * Заметка мастера отдельным ответом ({@code GET /api/bookings/{id}/note}); пара к {@link SetMasterNote}, общая для
 * областей, чтобы в схеме было одно имя.
 *
 * @param note текст заметки; {@code null} — заметки нет
 */
public record MasterNote(@Nullable String note) {}
