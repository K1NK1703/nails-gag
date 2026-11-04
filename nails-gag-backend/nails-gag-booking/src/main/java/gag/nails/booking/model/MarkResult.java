package gag.nails.booking.model;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Итог пакетной отметки одной записи.
 *
 * @param errorCode код ошибки, по которой запись не отмечена (как у отдельной отметки, например
 *     {@code booking.visit_not_started}); пусто — запись отмечена
 */
public record MarkResult(UUID bookingId, @Nullable String errorCode) {}
