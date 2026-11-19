package gag.nails.notifications.model;

/**
 * Каналы своего аккаунта: что включено и чем можно пользоваться. Сообщение уходит по каналу, только если он и включён,
 * и доступен.
 *
 * @param emailEnabled почта включена (без своей настройки — умолчание по роли)
 * @param emailAvailable почта настроена на сервере и у карточки клиента аккаунта есть адрес
 * @param telegramEnabled Telegram включён (без своей настройки — умолчание по роли)
 * @param telegramLinked чат Telegram привязан к аккаунту
 * @param telegramAvailable бот настроен на сервере: без него привязать чат нельзя
 */
public record MyChannels(
        boolean emailEnabled,
        boolean emailAvailable,
        boolean telegramEnabled,
        boolean telegramLinked,
        boolean telegramAvailable) {}
