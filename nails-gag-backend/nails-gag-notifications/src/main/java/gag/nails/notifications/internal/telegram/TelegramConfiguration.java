package gag.nails.notifications.internal.telegram;

import gag.nails.notifications.internal.NotificationsProperties;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Telegram-бот уведомлений (спека §3.6, §3.9): клиент Bot API, канал {@link TelegramChannel} и приём сообщений
 * {@link TelegramPoller}. Без токена бота ({@code nails-gag.notifications.telegram.bot-token}) клиент не создаёт
 * соединений, канал не настроен, приём не запускается. Приёма нет и при выключенных задачах модуля
 * ({@code nails-gag.notifications.jobs.enabled=false}) — так в тестах.
 */
@Configuration(proxyBeanMethods = false)
class TelegramConfiguration {

    @Bean
    TelegramApi telegramApi(NotificationsProperties properties) {
        var telegram = properties.telegram();
        return telegram.configured()
                ? TelegramApi.connect(telegram.apiUrl(), telegram.botToken(), telegram.pollTimeout())
                : TelegramApi.disabled();
    }

    @Bean
    TelegramChannel telegramChannel(TelegramApi api) {
        return new TelegramChannel(api);
    }

    @Bean
    @ConditionalOnProperty(name = "nails-gag.notifications.jobs.enabled", havingValue = "true", matchIfMissing = true)
    TelegramPoller telegramPoller(
            TelegramApi api, TelegramUpdates updates, NotificationsProperties properties, Clock clock) {
        return new TelegramPoller(
                api, updates::handle, properties.telegram().pollTimeout(), clock, TelegramPoller.Pause.SLEEP);
    }
}
