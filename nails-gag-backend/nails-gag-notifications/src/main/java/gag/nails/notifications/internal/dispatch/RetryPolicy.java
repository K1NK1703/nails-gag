package gag.nails.notifications.internal.dispatch;

import java.time.Duration;
import java.util.List;

/**
 * Повторы доставки (спека §3.5): пауза после 1-й неудачи — 1 минута, затем 5, 30 и 120 минут; пятая неудача —
 * доставка неудачна ({@code transient_exhausted}). Попытка засчитывается, когда отправка берёт доставку в аренду, —
 * поэтому и падение процесса посреди отправки не даёт повторять доставку без конца.
 */
final class RetryPolicy {

    /** Сколько всего попыток у доставки. */
    static final int MAX_ATTEMPTS = 5;

    /**
     * Аренда: на это время взятая доставка исчезает из созревших — параллельный прогон её не возьмёт, а после падения
     * процесса посреди отправки она созреет снова.
     */
    static final Duration LEASE = Duration.ofMinutes(5);

    private static final List<Duration> PAUSES =
            List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofMinutes(120));

    private RetryPolicy() {}

    /** Остались ли попытки после {@code attempts} сделанных. */
    static boolean canRetry(int attempts) {
        return attempts < MAX_ATTEMPTS;
    }

    /** Пауза после неудачной попытки номер {@code attempts} (с 1), пока попытки остаются. */
    static Duration pauseAfter(int attempts) {
        return PAUSES.get(Math.clamp(attempts, 1, PAUSES.size()) - 1);
    }
}
