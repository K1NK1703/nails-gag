package gag.nails.api.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code nails-gag.api.*}. Публичный внутри модуля: нужен подпакетам {@code internal} (защита, вход). Форму
 * значений при старте проверяет {@link ApiPropertiesGuard}.
 *
 * @param siteOrigin origin сайта ({@code https://nails.gag}): с ним сверяется {@code Origin} изменяющих запросов,
 *     от него строятся переходы после входа
 * @param cookies атрибуты cookie слоя
 * @param loginStateKey ключ шифрования cookie этапа входа, base64 ровно 32 байт; пустой вне {@code prod} —
 *     случайный ключ на время работы процесса
 * @param rateLimits лимиты частоты запросов в минуту
 * @param redirects пути на сайте, куда обратный вызов провайдера отправляет браузер
 */
@ConfigurationProperties("nails-gag.api")
public record ApiProperties(
        @DefaultValue("https://nails.gag") String siteOrigin,
        @DefaultValue Cookies cookies,
        @DefaultValue("") String loginStateKey,
        @DefaultValue RateLimits rateLimits,
        @DefaultValue Redirects redirects) {

    /**
     * @param secure атрибут {@code Secure}; выключается только в профиле {@code local} ради
     *     {@code http://localhost}
     */
    public record Cookies(@DefaultValue("true") boolean secure) {}

    /**
     * @param authPerMinute вход ({@code /api/auth/**}) с одного IP
     * @param anonymousPerMinute остальные запросы без сессии с одного IP
     * @param authenticatedPerMinute запросы с сессией одного аккаунта
     */
    public record RateLimits(
            @DefaultValue("10") int authPerMinute,
            @DefaultValue("60") int anonymousPerMinute,
            @DefaultValue("600") int authenticatedPerMinute) {}

    /**
     * Пути относительно {@link #siteOrigin()}.
     *
     * @param signedIn вход выполнен
     * @param registration нужна регистрация (билет в cookie)
     * @param linkConfirm привязка способа входа ждёт подтверждения
     * @param linked способ входа привязан
     * @param failure вход не удался; к пути добавляется {@code ?error=failed|blocked|provider}
     */
    public record Redirects(
            @DefaultValue("/cabinet") String signedIn,
            @DefaultValue("/register") String registration,
            @DefaultValue("/cabinet/account?link=confirm") String linkConfirm,
            @DefaultValue("/cabinet/account?link=done") String linked,
            @DefaultValue("/login") String failure) {}
}
