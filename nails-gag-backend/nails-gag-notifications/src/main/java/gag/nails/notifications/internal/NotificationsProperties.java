package gag.nails.notifications.internal;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.time.LocalTime;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.validation.annotation.Validated;

/**
 * {@code nails-gag.notifications.*}; неверные значения не дают приложению стартовать. Значения по умолчанию — только
 * здесь, в константах {@code DEFAULT_*}; адрес сайта, Telegram мастера и адрес салона умолчаний не имеют.
 *
 * @param jobs фоновые задачи; в тестах выключены
 * @param retentionDays сколько дней хранятся сообщения в конечном состоянии (с доставками), потом стираются уборкой
 * @param quietHours тихие часы клиенток по времени салона ({@code nails-gag.time-zone})
 * @param siteUrl адрес сайта для ссылок в сообщениях: схема и хост (с портом), без пути и завершающего слэша. Своя
 *     настройка, а не {@code nails-gag.api.site-origin}: модуль не зависит от {@code api}
 * @param masterTelegram Telegram мастера для подсказок в сообщениях, с {@code @}
 * @param salonAddress адрес салона для сообщений клиенткам
 * @param email почта
 * @param telegram Telegram-бот
 */
@Validated
@ConfigurationProperties("nails-gag.notifications")
public record NotificationsProperties(
        @DefaultValue @Valid Jobs jobs,

        @DefaultValue(DEFAULT_RETENTION_DAYS) @Positive int retentionDays,

        @DefaultValue @Valid QuietHoursWindow quietHours,
        @NotNull @Pattern(regexp = SITE_URL) String siteUrl,
        @NotNull @Pattern(regexp = TELEGRAM_HANDLE) String masterTelegram,
        @NotBlank String salonAddress,
        @DefaultValue @Valid Email email,
        @DefaultValue @Valid Telegram telegram) {

    public static final String DEFAULT_JOBS_ENABLED = "true";
    public static final String DEFAULT_DISPATCH_DELAY = "1m";
    public static final String DEFAULT_PURGE_CRON = "0 15 4 * * *";
    public static final String DEFAULT_RETENTION_DAYS = "90";
    public static final String DEFAULT_QUIET_FROM = "22:00";
    public static final String DEFAULT_QUIET_TO = "09:00";
    public static final String DEFAULT_POLL_TIMEOUT = "50s";
    public static final String DEFAULT_TELEGRAM_API_URL = "https://api.telegram.org";

    /** {@code http(s)://хост[:порт]} без пути, запроса и завершающего слэша. */
    public static final String SITE_URL = "https?://[^\\s/?#]+";

    /** Форма токена бота: {@code <id бота>:<секрет>}. */
    private static final java.util.regex.Pattern BOT_TOKEN = java.util.regex.Pattern.compile("[0-9]+:[A-Za-z0-9_-]+");

    /** Имя в Telegram с {@code @}: 5–32 символа латиницы, цифр и подчёркивания. */
    public static final String TELEGRAM_HANDLE = "@[A-Za-z0-9_]{5,32}";

    /**
     * Читаются аннотациями задач модуля ({@code @ConditionalOnProperty}, {@code @Scheduled}); поля здесь — чтобы все
     * настройки модуля были описаны в одном месте.
     *
     * @param enabled включены ли отправка, уборка и приём обновлений Telegram
     * @param dispatchDelay пауза между прогонами отправки (от конца одного до начала следующего)
     * @param purgeCron когда стираются старые сообщения и истёкшие ссылки привязки, по времени салона
     */
    public record Jobs(
            @DefaultValue(DEFAULT_JOBS_ENABLED) boolean enabled,

            @DefaultValue(DEFAULT_DISPATCH_DELAY) @NotNull @DurationMin(seconds = 1)
            Duration dispatchDelay,

            @DefaultValue(DEFAULT_PURGE_CRON) @NotNull String purgeCron) {

        /** Проверка при старте: выражение cron Spring из шести полей. */
        @AssertTrue(message = "purge-cron must be a valid cron expression")
        public boolean isPurgeCronValid() {
            return purgeCron != null && CronExpression.isValidExpression(purgeCron);
        }
    }

    /**
     * Окно тихих часов: с {@code from} включительно до {@code to} не включительно, через полночь, если {@code from}
     * позже {@code to}. Равные границы — тихих часов нет. Здесь только время суток; перенос момента из тихих часов на
     * их конец в поясе салона — отдельная чистая функция очереди, ей и оставлено имя {@code QuietHours}: она берёт
     * это окно из настроек.
     */
    public record QuietHoursWindow(
            @DefaultValue(DEFAULT_QUIET_FROM) @NotNull LocalTime from,

            @DefaultValue(DEFAULT_QUIET_TO) @NotNull LocalTime to) {

        /** Попадает ли время суток в тихие часы. */
        public boolean contains(LocalTime time) {
            if (from.isBefore(to)) {
                return !time.isBefore(from) && time.isBefore(to);
            }
            if (from.isAfter(to)) {
                return !time.isBefore(from) || time.isBefore(to);
            }
            return false;
        }
    }

    /**
     * Почта.
     *
     * @param from отправитель писем ({@code nails.gag <noreply@nails-gag.ru>}); пусто вместе с
     *     {@code spring.mail.host} — почта выключена
     */
    public record Email(@DefaultValue("") @NotNull String from) {

        /** Проверка при старте: непустой отправитель — один адрес по RFC 822, можно с именем. */
        @AssertTrue(message = "from must be a valid email address")
        public boolean isFromValid() {
            if (from == null || from.isBlank()) {
                return true;
            }
            try {
                new InternetAddress(from, true);
                return true;
            } catch (AddressException e) {
                return false;
            }
        }
    }

    /**
     * Бот. Токен — секрет: {@link #toString()} его не показывает.
     *
     * @param botToken токен от @BotFather; пусто — Telegram выключен
     * @param botUsername имя бота без {@code @} для ссылки привязки {@code t.me/<бот>}; обязательно при токене
     * @param pollTimeout сколько Telegram держит запрос {@code getUpdates} без новых сообщений
     * @param apiUrl адрес Bot API — схема и хост без пути: {@code https://api.telegram.org} или свой сервер Bot API;
     *     тесты подставляют сюда заглушку
     */
    public record Telegram(
            @DefaultValue("") @NotNull String botToken,

            @DefaultValue("") @NotNull @Pattern(regexp = "|[A-Za-z0-9_]{5,32}")
            String botUsername,

            @DefaultValue(DEFAULT_POLL_TIMEOUT) @NotNull @DurationMin(seconds = 1) @DurationMax(seconds = 60)
            Duration pollTimeout,

            @DefaultValue(DEFAULT_TELEGRAM_API_URL) @NotNull @Pattern(regexp = SITE_URL)
            String apiUrl) {

        /** Связывание настроек — этим конструктором. */
        @ConstructorBinding
        public Telegram {}

        /** Прежняя форма: Bot API — {@value NotificationsProperties#DEFAULT_TELEGRAM_API_URL}. */
        public Telegram(String botToken, String botUsername, Duration pollTimeout) {
            this(botToken, botUsername, pollTimeout, DEFAULT_TELEGRAM_API_URL);
        }

        /** Бот настроен: задан токен. */
        public boolean configured() {
            return !botToken.isBlank();
        }

        /**
         * Проверка при старте: токен — {@code <id бота>:<секрет>} из цифр, латиницы, {@code _} и {@code -}, как его
         * выдаёт @BotFather. Он часть адреса каждого запроса к Bot API: иной символ сломал бы адрес, а ошибка разбора
         * адреса показала бы его целиком. Проверка — методом, а не {@code @Pattern}: отвергнутое значение попадает в
         * сообщение о неудачном старте, а здесь это {@code false}, а не токен.
         */
        @AssertTrue(message = "bot-token must be <digits>:<latin letters, digits, _ or ->")
        public boolean isBotTokenWellFormed() {
            return !configured() || BOT_TOKEN.matcher(botToken).matches();
        }

        /** Проверка при старте: с токеном нужно и имя бота, иначе ссылку привязки не построить. */
        @AssertTrue(message = "bot-username is required when bot-token is set")
        public boolean isUsernameSetWhenConfigured() {
            return !configured() || !botUsername.isEmpty();
        }

        @Override
        public String toString() {
            return "Telegram[botToken=" + (configured() ? "<set>" : "<empty>") + ", botUsername=" + botUsername
                    + ", pollTimeout=" + pollTimeout + ", apiUrl=" + apiUrl + "]";
        }
    }
}
