package gag.nails.api.internal.security;

import io.github.bucket4j.TimeMeter;
import java.time.Clock;

/**
 * Время корзин Bucket4j — из {@link Clock} приложения, как всё «сейчас» в проекте: в тестах подставные часы
 * управляют пополнением корзин. Наносекунды от эпохи.
 */
final class ClockTimeMeter implements TimeMeter {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final Clock clock;

    ClockTimeMeter(Clock clock) {
        this.clock = clock;
    }

    @Override
    public long currentTimeNanos() {
        var now = clock.instant();
        return Math.addExact(Math.multiplyExact(now.getEpochSecond(), NANOS_PER_SECOND), now.getNano());
    }

    @Override
    public boolean isWallClockBased() {
        return true;
    }
}
