package gag.nails.notifications;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.identity.error.AccountNotFoundException;
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
     * @throws AccountNotFoundException аккаунта уже нет (удалён)
     */
    MyChannels update(UpdateMyChannels command, Actor actor);

    /**
     * Одноразовая ссылка привязки Telegram-бота к своему аккаунту: {@code https://t.me/<бот>?start=<токен>}, токен —
     * 43 символа {@code [A-Za-z0-9_-]}, действует 10 минут; новая ссылка заменяет прежнюю. Чат привязывается, когда
     * человек открывает ссылку и бот получает {@code /start <токен>}: прежний чат аккаунта и прежний аккаунт этого чата
     * отвязываются.
     *
     * @return ссылка и момент, до которого она действует
     * @throws AccessDeniedException у актора нет аккаунта
     * @throws TelegramUnavailableException бот не настроен на сервере (нет токена бота)
     * @throws AccountNotFoundException аккаунта уже нет (удалён)
     */
    TelegramLink createTelegramLink(Actor actor);

    /**
     * Отвязывает Telegram от своего аккаунта; без привязки ничего не делает.
     *
     * @throws AccessDeniedException у актора нет аккаунта
     */
    void unlinkTelegram(Actor actor);
}
