package gag.nails.booking.model;

/**
 * Правила записи, которые мастер настраивает сама.
 *
 * @param maxActiveBookings сколько активных записей может быть у клиента одновременно
 * @param bookingCutoffMinutes за сколько минут до начала слота запись и перенос клиентом закрываются
 * @param prepaymentHoldHours сколько часов запись ждёт предоплату
 * @param freeCancelWindowHours до скольких часов до визита отмена и перенос клиентом бесплатны
 * @param prepaymentPercent размер предоплаты в процентах — только для текстов
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
        int markCorrectionWindowDays) {}
