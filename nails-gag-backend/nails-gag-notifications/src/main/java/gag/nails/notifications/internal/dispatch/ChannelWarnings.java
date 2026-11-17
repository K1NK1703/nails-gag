package gag.nails.notifications.internal.dispatch;

import gag.nails.notifications.model.Channel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Сбой канала — WARN не чаще раза в минуту на канал (спека §6): при упавшем почтовом сервере каждая доставка пишет
 * сбой, и без ограничения лог заполнился бы одинаковыми строками. Пропущенные сбои считаются: их число называет
 * следующая строка канала или {@link #flush()} в конце прогона отправки, когда минута прошла. Исходы, которые зависят
 * от получателя (бот заблокирован, почта карточки не разбирается как адрес), — не сбой канала: INFO без ограничения.
 * Только id, вид, канал и код ошибки — без адресов и текстов.
 */
final class ChannelWarnings {

    static final Duration INTERVAL = Duration.ofMinutes(1);

    /**
     * Исходы, которые зависят от получателя, а не от канала: заблокированный бот и неразбираемая почта карточки
     * ({@code email_invalid_address} канала почты).
     */
    private static final Set<String> ORDINARY = Set.of("telegram_blocked", "email_invalid_address");

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);

    private final Clock clock;
    private final Map<Channel, Instant> lastWarned = new EnumMap<>(Channel.class);
    private final Map<Channel, Integer> suppressed = new EnumMap<>(Channel.class);

    ChannelWarnings(Clock clock) {
        this.clock = clock;
    }

    /** Сбой доставки {@code deliveryId} в канале; WARN — не чаще {@link #INTERVAL} на канал. */
    synchronized void failed(Channel channel, UUID deliveryId, UUID messageId, String kind, String errorCode) {
        if (ORDINARY.contains(errorCode)) {
            log.info(
                    "notification delivery {} of message {} ({}) via {} not delivered because of its recipient: {}",
                    deliveryId,
                    messageId,
                    kind,
                    channel,
                    errorCode);
            return;
        }
        var now = clock.instant();
        if (!due(channel, now)) {
            suppressed.merge(channel, 1, Integer::sum);
            return;
        }
        var skipped = take(channel, now);
        log.warn(
                "notification channel {} failed: delivery {} of message {} ({}), error {}; {} more failures since the"
                        + " previous warning",
                channel,
                deliveryId,
                messageId,
                kind,
                errorCode,
                skipped);
    }

    /** Называет пропущенные сбои каналов, по которым минута с последнего WARN уже прошла. */
    synchronized void flush() {
        var now = clock.instant();
        for (var channel : Channel.values()) {
            if (suppressed.getOrDefault(channel, 0) > 0 && due(channel, now)) {
                log.warn(
                        "notification channel {} failed {} more times since the previous warning",
                        channel,
                        take(channel, now));
            }
        }
    }

    private boolean due(Channel channel, Instant now) {
        var last = lastWarned.get(channel);
        return last == null || !now.isBefore(last.plus(INTERVAL));
    }

    private int take(Channel channel, Instant now) {
        lastWarned.put(channel, now);
        var skipped = suppressed.getOrDefault(channel, 0);
        suppressed.remove(channel);
        return skipped;
    }
}
