package gag.nails.api.internal.booking;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * Необязательное тело {@code POST /api/bookings/{id}/prepayment/received}: сумма полученной предоплаты. Без тела или
 * без суммы — сумма по умолчанию: процент предоплаты из правил записи от прайса выбранного состава, до рубля.
 *
 * @param amount сумма в рублях: больше нуля, не больше двух знаков после запятой и десяти до неё — проверяет модуль;
 *     {@code null} — сумма по умолчанию
 */
record ReceivePrepayment(@Nullable BigDecimal amount) {}
