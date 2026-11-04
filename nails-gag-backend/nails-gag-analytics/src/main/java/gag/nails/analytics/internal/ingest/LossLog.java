package gag.nails.analytics.internal.ingest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Лог потерянных событий с ограничением частоты: когда база недоступна или перегружена, сбой у каждого события
 * завалил бы лог. Первая потеря после тишины пишется сразу ({@code WARN}, у сбоя записи — с трассировкой); дальше —
 * не чаще раза в {@link #INTERVAL} одной строкой с числом потерь по причинам, без трассировки. Накопленное пишется со
 * следующей потерей или следующей успешной записью, когда интервал истёк. Время — из {@link Clock}; если часы ушли
 * назад, интервал считается истёкшим.
 *
 * <p>В лог не попадают ни IP, ни {@code User-Agent}: их здесь нет.
 */
final class LossLog {

    static final Duration INTERVAL = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(LossLog.class);

    private final Clock clock;
    private @Nullable Instant lastLine;
    private long failed;
    private long busy;

    LossLog(Clock clock) {
        this.clock = clock;
    }

    /** Событие не записано: сбой базы или ошибка в коде. */
    synchronized void failed(String what, RuntimeException failure) {
        failed++;
        report(what, failure);
    }

    /** Событие отброшено: все места записи заняты ({@code nails-gag.analytics.max-concurrent-writes}). */
    synchronized void busy() {
        busy++;
        report("dropped, too many concurrent writes", null);
    }

    /** После успешной записи: пишет накопленные потери, если интервал истёк. */
    synchronized void flush() {
        if (failed + busy > 0 && intervalPassed(clock.instant())) {
            summary(clock.instant());
        }
    }

    private void report(String what, @Nullable RuntimeException failure) {
        var now = clock.instant();
        if (!intervalPassed(now)) {
            return;
        }
        if (failed + busy == 1) {
            log.warn("analytics event lost: {}", what, failure);
            failed = 0;
            busy = 0;
            lastLine = now;
        } else {
            summary(now);
        }
    }

    private void summary(Instant now) {
        log.warn(
                "analytics lost {} events since {}: {} failed to be written, {} dropped as busy",
                failed + busy,
                lastLine,
                failed,
                busy);
        failed = 0;
        busy = 0;
        lastLine = now;
    }

    private boolean intervalPassed(Instant now) {
        return lastLine == null || now.isBefore(lastLine) || !now.isBefore(lastLine.plus(INTERVAL));
    }
}
