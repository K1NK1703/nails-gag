package gag.nails.api.internal.booking;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Тело {@code POST /api/bookings/{id}/reschedule}: перенос записи.
 *
 * @param slotId новый свободный слот, не совпадающий с текущим
 */
record RescheduleBooking(@NotNull UUID slotId) {}
