package gag.nails.api.internal.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import gag.nails.api.internal.ApiProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Clock;
import java.time.Duration;

/**
 * Корзины лимитов частоты (спецификация API, §5.3) в памяти процесса: один экземпляр бэкенда. Лимит «N в минуту» —
 * ёмкость N и пополнение N за минуту равномерно. Ключ корзины живёт {@link #IDLE} после последнего обращения; время
 * корзин — {@link ClockTimeMeter} поверх часов приложения.
 */
final class RateLimits {

    static final Duration IDLE = Duration.ofMinutes(10);
    static final Duration PERIOD = Duration.ofMinutes(1);

    /** Корзина и её лимит из {@code nails-gag.api.rate-limits}. */
    enum Kind {
        /** Вход ({@code /api/auth/**}) — по IP. */
        AUTH,
        /** Остальные запросы без сессии — по IP. */
        ANONYMOUS,
        /** Запросы с сессией — по аккаунту. */
        ACCOUNT
    }

    private record Key(Kind kind, String id) {}

    private final ApiProperties.RateLimits limits;
    private final ClockTimeMeter time;
    private final Cache<Key, Bucket> buckets =
            Caffeine.newBuilder().expireAfterAccess(IDLE).build();

    RateLimits(ApiProperties.RateLimits limits, Clock clock) {
        this.limits = limits;
        this.time = new ClockTimeMeter(clock);
    }

    /** Берёт один запрос из корзины {@code kind} с ключом {@code id}; отказ несёт время до пополнения. */
    ConsumptionProbe tryConsume(Kind kind, String id) {
        return buckets.get(new Key(kind, id), key -> bucket(perMinute(key.kind())))
                .tryConsumeAndReturnRemaining(1);
    }

    private int perMinute(Kind kind) {
        return switch (kind) {
            case AUTH -> limits.authPerMinute();
            case ANONYMOUS -> limits.anonymousPerMinute();
            case ACCOUNT -> limits.authenticatedPerMinute();
        };
    }

    private Bucket bucket(int perMinute) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(perMinute)
                        .refillGreedy(perMinute, PERIOD)
                        .build())
                .withCustomTimePrecision(time)
                .build();
    }
}
