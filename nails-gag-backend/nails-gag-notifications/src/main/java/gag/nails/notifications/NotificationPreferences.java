package gag.nails.notifications;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.notifications.command.UpdateMyChannels;
import gag.nails.notifications.error.TelegramUnavailableException;
import gag.nails.notifications.model.MyChannels;
import gag.nails.notifications.model.TelegramLink;

/**
 * Каналы своего аккаунта: любая сессия, раздел прав не нужен. У актора без аккаунта (внутренние и тестовые акторы)
 * своих каналов нет — {@link AccessDeniedException}.
 *
 * <p>Без своей настройки действуют умолчания: сотрудник, получающий мастерские сообщения (итоговый уровень раздела
 * «Записи» не ниже чтения), с ролью {@code MASTER} — только Telegram, без неё — ничего; остальные аккаунты (клиентки)
 * — почта и Telegram.
 */
public interface NotificationPreferences {

    /**
     * Свои каналы.
     *
     * @throws AccessDeniedException у актора нет аккаунта
     */
    MyChannels mine(Actor actor);

    /**
     * Включает и выключает свои каналы; дальше умолчания по роли к аккаунту не применяются.
     *
     * @return каналы после изменения, как {@link #mine}
     * @throws AccessDeniedException у актора нет аккаунта
     */
    MyChannels update(UpdateMyChannels command, Actor actor);

    /**
     * Одноразовая ссылка привязки Telegram-бота к своему аккаунту; новая ссылка заменяет прежнюю.
     *
     * @throws AccessDeniedException у актора нет аккаунта
     * @throws TelegramUnavailableException бот не настроен на сервере
     */
    TelegramLink createTelegramLink(Actor actor);

    /**
     * Отвязывает Telegram от своего аккаунта; без привязки ничего не делает.
     *
     * @throws AccessDeniedException у актора нет аккаунта
     */
    void unlinkTelegram(Actor actor);
}
