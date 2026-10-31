package gag.nails.booking.internal.booking;

import gag.nails.booking.model.BookingStatus;
import gag.nails.booking.model.CancelReason;
import gag.nails.booking.model.PrepaymentOutcome;
import org.jspecify.annotations.Nullable;

/**
 * Правило исхода полученной предоплаты (спецификация финансов §3.3) в одном месте: его используют представление
 * записи и денежные факты для финансов. Вычисления без базы.
 */
public final class PrepaymentOutcomes {

    private PrepaymentOutcomes() {}

    /**
     * Исход с учётом переопределения мастером.
     *
     * @param prepaymentReceived получена ли предоплата по записи
     * @param override переопределение: {@link PrepaymentOutcome#KEPT} или {@link PrepaymentOutcome#RETURNED}, только
     *     у отменённой записи и неявки
     * @return пусто, если предоплаты не было
     */
    public static @Nullable PrepaymentOutcome of(
            BookingStatus status,
            @Nullable CancelReason cancelReason,
            boolean prepaymentReceived,
            @Nullable PrepaymentOutcome override) {
        if (!prepaymentReceived) {
            return null;
        }
        return override != null ? override : byRule(status, cancelReason);
    }

    /**
     * Исход по умолчанию для записи с полученной предоплатой: активная или выполненная — в счёт визита; неявка,
     * поздняя отмена клиентом и отказ из-за опоздания (то, что ставит клиенту признак обязательной предоплаты) —
     * удержана; отмена в окне, мастером и удалением аккаунта — возвращена. Отмена по сроку предоплаты с полученной
     * предоплатой невозможна; если бы она случилась, удерживать было бы нечего — возвращена.
     */
    public static PrepaymentOutcome byRule(BookingStatus status, @Nullable CancelReason cancelReason) {
        return switch (status) {
            case AWAITING_PREPAYMENT, CONFIRMED, COMPLETED -> PrepaymentOutcome.APPLIED;
            case NO_SHOW -> PrepaymentOutcome.KEPT;
            // У отменённой записи причина есть всегда (CHECK в базе); без неё удерживать не за что.
            case CANCELLED -> cancelReason == null ? PrepaymentOutcome.RETURNED : byCancelReason(cancelReason);
        };
    }

    private static PrepaymentOutcome byCancelReason(CancelReason reason) {
        return switch (reason) {
            case CLIENT_LATE, LATE_ARRIVAL -> PrepaymentOutcome.KEPT;
            case CLIENT, MASTER, PREPAYMENT_TIMEOUT, ACCOUNT_DELETED -> PrepaymentOutcome.RETURNED;
        };
    }
}
