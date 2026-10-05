package gag.nails.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Запись без заметки мастера (её отдаёт {@link BookingQueries#masterNote}).
 *
 * @param slotStartsAt начало слота — копия на момент записи или переноса
 * @param comment комментарий клиента, до 500 символов; может отсутствовать
 * @param prepaymentRequired требовалась ли предоплата по этой записи
 * @param prepaymentDeadline срок предоплаты; есть только в состоянии {@link BookingStatus#AWAITING_PREPAYMENT}
 * @param prepaymentReceivedAt когда мастер отметил предоплату; пусто, если её не было
 * @param earlierTime последнее предложение приехать раньше; пусто, если его не было
 * @param agreedStart согласованное более раннее начало; пусто — визит начинается в начале слота
 * @param cancelReason причина отмены; есть только у отменённой записи
 * @param plannedServices состав, выбранный при записи: основная услуга первой, затем дополнения
 * @param actualServices фактический состав; пуст, пока визит не отмечен выполненным
 * @param finalAmount итоговая сумма визита; пусто, пока визит не отмечен выполненным
 */
public record BookingView(
        UUID id,
        UUID clientId,
        UUID slotId,
        Instant slotStartsAt,
        Instant slotEndsAt,
        BookingStatus status,
        BookingCreator createdBy,
        @Nullable String comment,
        boolean prepaymentRequired,
        @Nullable Instant prepaymentDeadline,
        @Nullable Instant prepaymentReceivedAt,
        @Nullable EarlierTimeView earlierTime,
        @Nullable Instant agreedStart,
        @Nullable CancelReason cancelReason,
        @Nullable Instant cancelledAt,
        @Nullable Instant completedAt,
        @Nullable Instant noShowAt,
        List<BookedServiceView> plannedServices,
        List<BookedServiceView> actualServices,
        @Nullable BigDecimal finalAmount) {

    /** Время, к которому клиент приходит: согласованное более раннее или начало слота. */
    public Instant visitStart() {
        return agreedStart != null ? agreedStart : slotStartsAt;
    }
}
