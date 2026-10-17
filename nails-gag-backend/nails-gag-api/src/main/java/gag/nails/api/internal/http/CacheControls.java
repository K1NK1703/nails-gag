package gag.nails.api.internal.http;

import java.time.Duration;
import org.springframework.http.CacheControl;

/** {@code Cache-Control} ответов, которые отличаются от умолчания {@code no-store} (спецификация API, §4.2). */
public final class CacheControls {

    /**
     * Публичные ответы ({@code /api/price-list}, {@code /api/documents}): один для всех, кэш на 5 минут. Заданный
     * адресом заголовок заменяет {@code no-store} фильтра заголовков.
     */
    public static final CacheControl PUBLIC =
            CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private CacheControls() {}
}
