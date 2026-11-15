package gag.nails.notifications.internal.preference;

import gag.nails.clients.ClientQueries;
import gag.nails.clients.model.ClientView;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.id.UuidV7;
import gag.nails.notifications.NotificationPreferences;
import gag.nails.notifications.command.UpdateMyChannels;
import gag.nails.notifications.error.TelegramUnavailableException;
import gag.nails.notifications.internal.NotificationsProperties;
import gag.nails.notifications.model.MyChannels;
import gag.nails.notifications.model.TelegramLink;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultNotificationPreferences implements NotificationPreferences {

    private final PreferenceRepository preferences;
    private final TelegramChatRepository chats;
    private final ChannelDefaults defaults;
    private final ClientQueries clients;
    private final NotificationsProperties properties;
    private final Clock clock;

    DefaultNotificationPreferences(
            PreferenceRepository preferences,
            TelegramChatRepository chats,
            ChannelDefaults defaults,
            ClientQueries clients,
            NotificationsProperties properties,
            Clock clock) {
        this.preferences = preferences;
        this.chats = chats;
        this.defaults = defaults;
        this.clients = clients;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public MyChannels mine(Actor actor) {
        return channels(requireAccount(actor));
    }

    @Override
    public MyChannels update(UpdateMyChannels command, Actor actor) {
        var accountId = requireAccount(actor);
        preferences.upsert(
                UuidV7.next(), accountId, command.emailEnabled(), command.telegramEnabled(), clock.instant());
        return channels(accountId);
    }

    /**
     * Ссылку выдаёт бот, а приёма его обновлений в модуле пока нет: привязка недоступна, даже если токен задан.
     * Аккаунт проверяется первым — актор без аккаунта получает отказ, а не «бот недоступен».
     */
    @Override
    public TelegramLink createTelegramLink(Actor actor) {
        requireAccount(actor);
        throw new TelegramUnavailableException();
    }

    @Override
    public void unlinkTelegram(Actor actor) {
        chats.deleteByAccountId(requireAccount(actor));
    }

    private MyChannels channels(UUID accountId) {
        var switches = preferences
                .findByAccountId(accountId)
                .map(PreferenceEntity::switches)
                .orElseGet(() -> defaults.forAccount(accountId));
        var emailAvailable = clients.byAccountId(accountId)
                .map(ClientView::email)
                .filter(email -> !email.isBlank())
                .isPresent();
        return new MyChannels(
                switches.email(),
                emailAvailable,
                switches.telegram(),
                chats.existsByAccountId(accountId),
                properties.telegram().configured());
    }

    private static UUID requireAccount(Actor actor) {
        var accountId = actor.accountId();
        if (accountId == null) {
            throw new AccessDeniedException();
        }
        return accountId;
    }
}
