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

    /**
     * Картинка опубликованной работы портфолио: кэш на час, а не навсегда — после отзыва согласия копия в nginx и
     * браузерах живёт не дольше часа, сам адрес сразу отвечает {@code 404}.
     */
    public static final CacheControl PUBLIC_IMAGE =
            CacheControl.maxAge(Duration.ofHours(1)).cachePublic();

    /** Ответ, который нельзя сохранять: закрытые данные, явно — там, где ответ иначе мог бы кэшироваться. */
    public static final CacheControl NO_STORE = CacheControl.noStore();

    private CacheControls() {}
}
