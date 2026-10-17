package gag.nails.api.internal.booking;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * Тело {@code POST /api/bookings/{id}/earlier-time}: предложение клиенту приехать раньше начала слота.
 *
 * @param proposedStart предложенное время, {@code "2026-10-08T09:00:00Z"}: в день слота по поясу салона, позже
 *     «сейчас» и раньше начала слота — это проверяет модуль ({@code 400 common.validation})
 */
record ProposeEarlierTime(@NotNull Instant proposedStart) {}
