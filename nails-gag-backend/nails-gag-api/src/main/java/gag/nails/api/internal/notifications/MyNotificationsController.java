package gag.nails.api.internal.notifications;

import gag.nails.common.actor.Actor;
import gag.nails.notifications.NotificationPreferences;
import gag.nails.notifications.command.UpdateMyChannels;
import gag.nails.notifications.model.MyChannels;
import gag.nails.notifications.model.TelegramLink;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Каналы уведомлений своего аккаунта и привязка Telegram (спецификация уведомлений, §3.2, §3.6, §4): любой сессии,
 * раздел прав не нужен; без сессии — {@code 401}. Ответы — {@code Cache-Control: no-store}.
 *
 * <p>Аккаунт, удалённый, пока шёл запрос (сессия прошла проверку до фиксации удаления), — {@code 404}, как у
 * {@code GET}/{@code DELETE /api/me}: модуль бросает {@code AccountNotFoundException}, клиенту она отвечает
 * {@code common.not_found}, сотруднику — {@code identity.account_not_found} (спецификация API, §4.6). Следующий запрос
 * с той же cookie — уже {@code 401}: удаление закрывает все сессии аккаунта.
 */
@RestController
@RequestMapping("/api/notifications/me")
class MyNotificationsController {

    private final NotificationPreferences preferences;

    MyNotificationsController(NotificationPreferences preferences) {
        this.preferences = preferences;
    }

    /**
     * Свои каналы: что включено (без своей настройки — умолчания по роли) и чем можно пользоваться — почта настроена на
     * сервере и есть в карточке, бот настроен, чат привязан.
     */
    @GetMapping
    MyChannels mine(Actor actor) {
        return preferences.mine(actor);
    }

    /**
     * Включает и выключает свои каналы, оба сразу; дальше умолчания по роли к аккаунту не применяются. Ответ — каналы
     * после изменения, как у {@code GET}.
     */
    @PutMapping
    MyChannels update(@Valid @RequestBody UpdateMyChannels body, Actor actor) {
        return preferences.update(body, actor);
    }

    /**
     * Одноразовая ссылка {@code https://t.me/<бот>?start=<токен>} на 10 минут; новая заменяет прежнюю. Чат
     * привязывается, когда человек откроет ссылку и нажмёт «Старт» у бота. Бот не настроен на сервере —
     * {@code 409 notifications.telegram_unavailable}.
     */
    @PostMapping("/telegram-link")
    @ResponseStatus(HttpStatus.CREATED)
    TelegramLink createTelegramLink(Actor actor) {
        return preferences.createTelegramLink(actor);
    }

    /** Отвязывает Telegram от своего аккаунта; без привязки — тоже {@code 204}. */
    @DeleteMapping("/telegram")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unlinkTelegram(Actor actor) {
        preferences.unlinkTelegram(actor);
    }
}
