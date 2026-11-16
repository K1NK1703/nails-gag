package gag.nails.booking.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Правила записи, которые мастер настраивает сама.
 *
 * @param maxActiveBookings сколько активных записей может быть у клиента одновременно
 * @param bookingCutoffMinutes за сколько минут до начала слота запись и перенос клиентом закрываются
 * @param prepaymentHoldHours сколько часов запись ждёт предоплату
 * @param freeCancelWindowHours до скольких часов до визита отмена и перенос клиентом бесплатны
 * @param prepaymentPercent размер предоплаты в процентах — для текстов и суммы предоплаты по умолчанию (от прайса)
 * @param lateArrivalToleranceMinutes допустимое опоздание — только для текстов
 * @param markCorrectionWindowDays сколько дней после слота можно исправлять отметку и вносить визит задним числом
 */
public record BookingSettingsView(
        int maxActiveBookings,
        int bookingCutoffMinutes,
        int prepaymentHoldHours,
        int freeCancelWindowHours,
        int prepaymentPercent,
        int lateArrivalToleranceMinutes,
        int markCorrectionWindowDays) {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    /**
     * Сумма предоплаты по умолчанию: процент предоплаты от прайса выбранного при записи состава, округлённый до рубля
     * (половина — вверх), но не меньше рубля; два знака после запятой. Её отмечает {@code BookingPrepayments}, если
     * сотрудник не ввёл свою, и её же называют клиенту в сообщении о предоплате. Это же правило повторяет миграция
     * {@code booking/0005-finance.sql} для предоплат, полученных до появления суммы.
     */
    public BigDecimal defaultPrepaymentAmount(BigDecimal plannedPrice) {
        var amount = plannedPrice
                .multiply(BigDecimal.valueOf(prepaymentPercent))
                .divide(ONE_HUNDRED, 0, RoundingMode.HALF_UP);
        return amount.max(BigDecimal.ONE).setScale(2, RoundingMode.UNNECESSARY);
    }
}
