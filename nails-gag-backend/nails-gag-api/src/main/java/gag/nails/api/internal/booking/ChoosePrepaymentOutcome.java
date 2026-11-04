package gag.nails.api.internal.booking;

import gag.nails.booking.model.PrepaymentOutcome;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Тело {@code PUT /api/bookings/{id}/prepayment/outcome}: исход полученной предоплаты отменённой записи или неявки.
 *
 * @param outcome {@code KEPT} — удержана или {@code RETURNED} — возвращена; {@code APPLIED} вычисляется сам и
 *     выбрать его нельзя — {@code 400 common.validation} с полем {@code outcome}, поэтому в схеме его нет
 */
record ChoosePrepaymentOutcome(
        @Schema(allowableValues = {"KEPT", "RETURNED"}) @NotNull
        PrepaymentOutcome outcome) {}
