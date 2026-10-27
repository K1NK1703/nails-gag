package gag.nails.api.internal.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import gag.nails.api.internal.ApiProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * Корзины лимитов частоты (спецификация API, §5.3) в памяти процесса: один экземпляр бэкенда. Лимит «N за период» —
 * ёмкость N и пополнение N за период равномерно (минута, у загрузок фото — час); время корзин — {@link ClockTimeMeter}
 * поверх часов приложения.
 *
 * <p>Ключ корзины забывается через свой период без обращений: за это время корзина пополняется целиком и ничем не
 * отличается от новой. Раньше забывать нельзя — новая полная корзина обошла бы лимит. Каждый вид корзины — в своём
 * кэше с потолком числа ключей: память ограничена, а поток анонимных запросов с множества адресов не вытесняет корзины
 * входа и аккаунтов. Вытесненная корзина — просто новая полная; чтобы выиграть от этого, нужно больше разных адресов в
 * минуту, чем потолок.
 */
final class RateLimits {

    static final Duration PERIOD = Duration.ofMinutes(1);

    /** Период корзины загрузок фото. */
    static final Duration UPLOAD_PERIOD = Duration.ofHours(1);

    /** Потолок ключей корзин по IP (вход, анонимы, картинки): с запасом больше живых клиентов салона за минуту. */
    static final long IP_KEYS = 100_000;

    /** Потолок ключей корзин по аккаунту: аккаунтов заведомо меньше, чем адресов. */
    static final long ACCOUNT_KEYS = 50_000;

    /** IPv6 клиента ключуется сетью /64: столько адресов обычно получает один абонент. */
    private static final int IPV6_PREFIX_BYTES = 8;

    /** Корзина и её лимит из {@code nails-gag.api.rate-limits}. */
    enum Kind {
        /** Вход ({@code /api/auth/**}) — по IP. */
        AUTH,
        /** Остальные запросы без сессии — по IP. */
        ANONYMOUS,
        /** Запросы с сессией — по аккаунту. */
        ACCOUNT,
        /** Загрузки фото ({@code POST /api/files}) — по аккаунту, вдобавок к {@link #ACCOUNT}. */
        UPLOAD,
        /** Картинки портфолио ({@code /api/portfolio/images/**}) — по IP, вместо остальных корзин. */
        IMAGE;

        /** За сколько корзина пополняется целиком; столько же ключ живёт без обращений. */
        Duration period() {
            return this == UPLOAD ? UPLOAD_PERIOD : PERIOD;
        }

        private boolean byAccount() {
            return this == ACCOUNT || this == UPLOAD;
        }
    }

    private final ApiProperties.RateLimits limits;
    private final ClockTimeMeter time;
    private final Map<Kind, Cache<String, Bucket>> buckets = new EnumMap<>(Kind.class);

    RateLimits(ApiProperties.RateLimits limits, Clock clock) {
        this.limits = limits;
        this.time = new ClockTimeMeter(clock);
        for (var kind : Kind.values()) {
            buckets.put(
                    kind,
                    Caffeine.newBuilder()
                            .expireAfterAccess(kind.period())
                            .maximumSize(kind.byAccount() ? ACCOUNT_KEYS : IP_KEYS)
                            .build());
        }
    }

    /**
     * Берёт один запрос из корзины {@code kind} с ключом {@code key} (для корзин по IP — {@link #ipKey}); отказ несёт
     * время до пополнения.
     */
    ConsumptionProbe tryConsume(Kind kind, String key) {
        return buckets.get(kind)
                .get(key, ignored -> bucket(perPeriod(kind), kind.period()))
                .tryConsumeAndReturnRemaining(1);
    }

    /**
     * Ключ корзины по адресу клиента: IPv4 — сам адрес (в том числе записанный как IPv6 {@code ::ffff:a.b.c.d}), IPv6
     * — его сеть /64 ({@code 2001:db8:1:2::/64}), иначе смена адреса внутри своей сети обходила бы лимит. Зона IPv6
     * ({@code %eth0}) отбрасывается: адрес клиента она не меняет. Разбор — только литерала, без обращения к DNS; не
     * разобралось — строка как есть.
     */
    static String ipKey(String ip) {
        var zone = ip.indexOf('%');
        var literal = zone < 0 ? ip : ip.substring(0, zone);
        InetAddress address;
        try {
            address = InetAddress.ofLiteral(literal);
        } catch (IllegalArgumentException e) {
            return ip;
        }
        if (!(address instanceof Inet6Address)) {
            return address.getHostAddress();
        }
        var bytes = address.getAddress();
        var prefix = new StringBuilder();
        for (var i = 0; i < IPV6_PREFIX_BYTES; i += 2) {
            prefix.append(Integer.toHexString(((bytes[i] & 0xff) << 8) | (bytes[i + 1] & 0xff)))
                    .append(':');
        }
        return prefix.append(":/64").toString();
    }

    /**
     * Возвращает один жетон в корзину {@code kind} с ключом {@code key} — за запрос, который не состоялся по вине
     * сервера. Сверх ёмкости корзина не наполняется; забытой корзины нет — и возвращать некуда.
     */
    void refund(Kind kind, String key) {
        var bucket = buckets.get(kind).getIfPresent(key);
        if (bucket != null) {
            bucket.addTokens(1);
        }
    }

    private int perPeriod(Kind kind) {
        return switch (kind) {
            case AUTH -> limits.authPerMinute();
            case ANONYMOUS -> limits.anonymousPerMinute();
            case ACCOUNT -> limits.authenticatedPerMinute();
            case UPLOAD -> limits.uploadsPerHour();
            case IMAGE -> limits.imagesPerMinute();
        };
    }

    private Bucket bucket(int perPeriod, Duration period) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(perPeriod)
                        .refillGreedy(perPeriod, period)
                        .build())
                .withCustomTimePrecision(time)
                .build();
    }
}
