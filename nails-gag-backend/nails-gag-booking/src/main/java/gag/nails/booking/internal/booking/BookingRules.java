package gag.nails.booking.internal.booking;

import gag.nails.booking.model.BookingSettingsView;
import java.time.Duration;
import java.time.Instant;

/** Временные правила записи для одного момента: настройки плюс «сейчас». Вычисления без базы. */
public record BookingRules(BookingSettingsView settings, Instant now) {

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

    /** Отметку визита ещё можно исправить; ровно на границе — можно. */
    public boolean correctionWindowOpen(Instant slotStartsAt) {
        return !now.isAfter(slotStartsAt.plus(correctionWindow()));
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
