package gag.nails.api.internal.clients;

import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Тело {@code PUT /api/clients/{id}/prepayment-required}: признак обязательной предоплаты вручную.
 *
 * @param required ставится ли признак
 * @param comment пояснение для журнала признака, до 500 символов, как в модуле
 */
record SetPrepaymentRequired(
        boolean required, @Size(max = 500) @Nullable String comment) {}
