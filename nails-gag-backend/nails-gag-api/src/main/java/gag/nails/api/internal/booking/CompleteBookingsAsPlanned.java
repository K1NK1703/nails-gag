package gag.nails.api.internal.booking;

import gag.nails.booking.VisitMarks;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Тело {@code POST /api/bookings/complete-as-planned}: записи из списка «ждут отметки», которые мастер отмечает
 * выполненными по прайсу. Сколько их можно передать, проверяет модуль.
 *
 * @param bookingIds не больше {@value VisitMarks#MAX_BATCH} идентификаторов, считая повторы; повторы убираются
 */
record CompleteBookingsAsPlanned(
        @ArraySchema(maxItems = VisitMarks.MAX_BATCH) @NotNull
        List<@NotNull UUID> bookingIds) {}
