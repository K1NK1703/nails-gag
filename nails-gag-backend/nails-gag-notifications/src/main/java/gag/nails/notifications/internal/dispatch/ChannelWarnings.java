package gag.nails.notifications.internal.dispatch;

import gag.nails.notifications.model.Channel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Сбой канала — WARN не чаще раза в минуту на канал (спека §6): при упавшем почтовом сервере каждая доставка
 * пишет сбой, и без ограничения лог заполнился бы одинаковыми строками. Пропущенные сбои считаются и называются в
 * следующей строке. Только id, вид, канал и код ошибки — без адресов и текстов.
 */
final class ChannelWarnings {

    static final Duration INTERVAL = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);

    private final Clock clock;
    private final Map<Channel, Instant> lastWarned = new EnumMap<>(Channel.class);
    private final Map<Channel, Integer> suppressed = new EnumMap<>(Channel.class);

    ChannelWarnings(Clock clock) {
        this.clock = clock;
    }

    /** Сбой доставки {@code deliveryId} в канале; в лог — не чаще {@link #INTERVAL} на канал. */
    synchronized void failed(Channel channel, UUID deliveryId, UUID messageId, String kind, String errorCode) {
        var now = clock.instant();
        var last = lastWarned.get(channel);
        if (last != null && now.isBefore(last.plus(INTERVAL))) {
            suppressed.merge(channel, 1, Integer::sum);
            return;
        }
        var skipped = suppressed.getOrDefault(channel, 0);
        lastWarned.put(channel, now);
        suppressed.remove(channel);
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
}
