package gag.nails.analytics.internal.ingest;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Роботы по {@code User-Agent}: поисковики, превью ссылок в мессенджерах, безголовые браузеры и консольные клиенты.
 * Признаки ищутся без учёта регистра; пустой заголовок — тоже робот (браузеры его шлют всегда).
 */
final class Robots {

    private static final List<String> MARKERS = List.of(
            "bot",
            "crawl",
            "spider",
            "slurp",
            "headless",
            "preview",
            "facebookexternalhit",
            "telegrambot",
            "whatsapp",
            "curl",
            "wget",
            "python",
            "java/");

    private Robots() {}

    static boolean isRobot(@Nullable String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return true;
        }
        var lower = userAgent.toLowerCase(Locale.ROOT);
        return MARKERS.stream().anyMatch(lower::contains);
    }
}
