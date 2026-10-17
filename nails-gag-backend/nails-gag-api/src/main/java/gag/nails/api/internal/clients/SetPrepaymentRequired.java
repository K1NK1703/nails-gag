package gag.nails.api.internal.clients;

import org.jspecify.annotations.Nullable;

/**
 * Тело {@code PUT /api/clients/{id}/prepayment-required}: признак обязательной предоплаты вручную.
 *
 * @param required ставится ли признак
 * @param comment пояснение для журнала признака. Длину (до 500 символов) проверяет модуль после проверки прав —
 *     {@code 400 common.validation} с полем {@code comment}
 */
record SetPrepaymentRequired(boolean required, @Nullable String comment) {}
