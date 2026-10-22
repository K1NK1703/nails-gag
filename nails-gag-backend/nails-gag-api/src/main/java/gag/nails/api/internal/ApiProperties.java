package gag.nails.api.internal;

import gag.nails.common.error.InternalException;
import java.util.Base64;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code nails-gag.api.*}. Публичный внутри модуля: нужен подпакетам {@code internal} (защита, вход). Форму
 * значений при старте проверяет {@link ApiPropertiesGuard}.
 *
 * @param siteOrigin origin сайта ({@code https://nails-gag.ru}): с ним сверяется {@code Origin} изменяющих запросов,
 *     от него строятся переходы после входа
 * @param cookies атрибуты cookie слоя
 * @param loginStateKey ключ шифрования cookie этапа входа, base64 ровно 32 байт; пустой вне {@code prod} —
 *     случайный ключ на время работы процесса
 * @param rateLimits лимиты частоты запросов
 * @param redirects пути на сайте, куда обратный вызов провайдера отправляет браузер
 */
@ConfigurationProperties("nails-gag.api")
public record ApiProperties(
        @DefaultValue("https://nails-gag.ru") String siteOrigin,
        @DefaultValue Cookies cookies,
        @DefaultValue("") String loginStateKey,
        @DefaultValue RateLimits rateLimits,
        @DefaultValue Redirects redirects) {

    /** Длина ключа {@code login-state-key} в байтах: AES-256. */
    public static final int LOGIN_STATE_KEY_BYTES = 32;

    /**
     * Ключ шифрования cookie этапа входа в байтах; пусто, если он не задан. Одна проверка формы на всех: её зовут
     * {@link ApiPropertiesGuard} при старте и cookie этапа входа, когда берёт ключ.
     *
     * @throws InternalException значение — не base64 или не ровно {@value #LOGIN_STATE_KEY_BYTES} байта
     */
    public Optional<byte[]> loginStateKeyBytes() {
        if (loginStateKey.isBlank()) {
            return Optional.empty();
        }
        var key = "nails-gag.api.login-state-key";
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(loginStateKey);
        } catch (IllegalArgumentException e) {
            throw new InternalException(key + " is not valid base64", e);
        }
        if (bytes.length != LOGIN_STATE_KEY_BYTES) {
            throw new InternalException(key + " must decode to exactly " + LOGIN_STATE_KEY_BYTES + " bytes");
        }
        return Optional.of(bytes);
    }

    /**
     * @param secure атрибут {@code Secure}; выключается только в профиле {@code local} ради
     *     {@code http://localhost}
     */
    public record Cookies(@DefaultValue("true") boolean secure) {}

    /**
     * @param authPerMinute вход ({@code /api/auth/**}) с одного IP
     * @param anonymousPerMinute остальные запросы без сессии с одного IP
     * @param authenticatedPerMinute запросы с сессией одного аккаунта
     * @param uploadsPerHour загрузки фото ({@code POST /api/files}) одного аккаунта в час — вдобавок к
     *     {@code authenticatedPerMinute}: обработка картинки дорога, а места на диске конечны
     * @param imagesPerMinute картинки портфолио ({@code /api/portfolio/images/**}) с одного IP — своя корзина вместо
     *     общей: лендинг запрашивает их десятками
     */
    public record RateLimits(
            @DefaultValue("10") int authPerMinute,
            @DefaultValue("60") int anonymousPerMinute,
            @DefaultValue("600") int authenticatedPerMinute,
            @DefaultValue("30") int uploadsPerHour,
            @DefaultValue("600") int imagesPerMinute) {

        /** Загрузок в час по умолчанию — как в {@code @DefaultValue} компонента. */
        public static final int DEFAULT_UPLOADS_PER_HOUR = 30;

        /** Картинок портфолио в минуту по умолчанию — как в {@code @DefaultValue} компонента. */
        public static final int DEFAULT_IMAGES_PER_MINUTE = 600;

        /** Привязка настроек — только через этот конструктор. */
        @ConstructorBinding
        public RateLimits {}

        /**
         * Лимиты в минуту, загрузки ({@value #DEFAULT_UPLOADS_PER_HOUR} в час) и картинки
         * ({@value #DEFAULT_IMAGES_PER_MINUTE} в минуту) — по умолчанию.
         */
        public RateLimits(int authPerMinute, int anonymousPerMinute, int authenticatedPerMinute) {
            this(
                    authPerMinute,
                    anonymousPerMinute,
                    authenticatedPerMinute,
                    DEFAULT_UPLOADS_PER_HOUR,
                    DEFAULT_IMAGES_PER_MINUTE);
        }
    }

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
