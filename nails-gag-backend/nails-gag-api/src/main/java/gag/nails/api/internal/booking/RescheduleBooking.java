package gag.nails.api.internal.booking;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Тело {@code POST /api/bookings/{id}/reschedule}: перенос записи.
 *
 * @param newSlotId новый свободный слот; совпадающий с текущим — {@code 400 common.validation} с полем
 *     {@code newSlotId}
 */
record RescheduleBooking(@NotNull UUID newSlotId) {}
