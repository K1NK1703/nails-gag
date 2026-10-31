package gag.nails.booking.internal.booking;

import gag.nails.booking.model.BookingSettingsView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/** Временные правила записи для одного момента: настройки плюс «сейчас». Вычисления без базы. */
public record BookingRules(BookingSettingsView settings, Instant now) {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    /**
     * До начала слота меньше отсечки: клиенту запись и перенос сюда закрыты. Слот, который уже
     * начался или начинается прямо сейчас, закрыт при любой отсечке, в том числе нулевой.
     */
    boolean tooLateToBook(Instant slotStartsAt) {
        return slotStartsAt.isBefore(now.plus(cutoff())) || !slotStartsAt.isAfter(now);
    }

    /** Окно бесплатной отмены и переноса ещё открыто; ровно на границе — открыто. */
    boolean freeCancellationOpen(Instant slotStartsAt) {
        return !now.isAfter(slotStartsAt.minus(Duration.ofHours(settings.freeCancelWindowHours())));
    }

    public boolean visitStarted(Instant slotStartsAt) {
        return !now.isBefore(slotStartsAt);
    }

    /** Слот в прошлом глубже срока исправления: внести такой визит задним числом нельзя. */
    boolean olderThanCorrectionWindow(Instant slotStartsAt) {
        return slotStartsAt.isBefore(now.minus(correctionWindow()));
    }

    /**
     * Срок исправления, считая от {@code from}, ещё не истёк; ровно на границе — не истёк. Для отметки визита срок
     * считается от начала слота, для исхода предоплаты — от отмены или неявки.
     */
    public boolean correctionWindowOpen(Instant from) {
        return !now.isAfter(from.plus(correctionWindow()));
    }

    /**
     * Сумма предоплаты по умолчанию: процент предоплаты из настроек от прайса выбранного при записи состава,
     * округлённый до рубля (половина — вверх), но не меньше рубля. Это же правило повторяет миграция
     * {@code booking/0005-finance.sql} для предоплат, полученных до появления суммы.
     */
    public BigDecimal defaultPrepaymentAmount(BigDecimal plannedPrice) {
        var amount = plannedPrice
                .multiply(BigDecimal.valueOf(settings.prepaymentPercent()))
                .divide(ONE_HUNDRED, 0, RoundingMode.HALF_UP);
        return amount.max(BigDecimal.ONE).setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * Срок предоплаты новой записи: через время ожидания, но не позже отсечки перед слотом. Если
     * это время уже прошло (запись незадолго до слота) — до начала слота.
     */
    Instant prepaymentDeadline(Instant slotStartsAt) {
        var hold = Duration.ofHours(settings.prepaymentHoldHours());
        return notPast(earlier(now.plus(hold), slotStartsAt.minus(cutoff())), slotStartsAt);
    }

    /** Срок после переноса: прежний не продлевается, но укладывается в отсечку перед новым слотом. */
    Instant prepaymentDeadlineAfterReschedule(Instant currentDeadline, Instant newSlotStartsAt) {
        return notPast(earlier(currentDeadline, newSlotStartsAt.minus(cutoff())), newSlotStartsAt);
    }

    private Duration cutoff() {
        return Duration.ofMinutes(settings.bookingCutoffMinutes());
    }

    private Duration correctionWindow() {
        return Duration.ofDays(settings.markCorrectionWindowDays());
    }

    private Instant notPast(Instant deadline, Instant slotStartsAt) {
        return deadline.isAfter(now) ? deadline : slotStartsAt;
    }

    private static Instant earlier(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }
}
