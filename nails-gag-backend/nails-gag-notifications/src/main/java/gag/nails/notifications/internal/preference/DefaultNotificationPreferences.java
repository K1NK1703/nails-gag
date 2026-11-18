package gag.nails.notifications.internal.preference;

import gag.nails.clients.ClientQueries;
import gag.nails.clients.model.ClientView;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.id.UuidV7;
import gag.nails.notifications.NotificationPreferences;
import gag.nails.notifications.command.UpdateMyChannels;
import gag.nails.notifications.error.TelegramUnavailableException;
import gag.nails.notifications.internal.MailServer;
import gag.nails.notifications.internal.NotificationsProperties;
import gag.nails.notifications.model.MyChannels;
import gag.nails.notifications.model.TelegramLink;
import java.net.URI;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultNotificationPreferences implements NotificationPreferences {

    private final PreferenceRepository preferences;
    private final TelegramChatRepository chats;
    private final LinkTokens linkTokens;
    private final ChannelDefaults defaults;
    private final ClientQueries clients;
    private final NotificationsProperties properties;
    private final MailServer mailServer;
    private final Clock clock;

    DefaultNotificationPreferences(
            PreferenceRepository preferences,
            TelegramChatRepository chats,
            LinkTokens linkTokens,
            ChannelDefaults defaults,
            ClientQueries clients,
            NotificationsProperties properties,
            MailServer mailServer,
            Clock clock) {
        this.preferences = preferences;
        this.chats = chats;
        this.linkTokens = linkTokens;
        this.defaults = defaults;
        this.clients = clients;
        this.properties = properties;
        this.mailServer = mailServer;
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
     * Ссылка {@code https://t.me/<бот>?start=<токен>} с новым токеном аккаунта ({@link LinkTokens}). Аккаунт
     * проверяется первым — актор без аккаунта получает отказ, а не «бот недоступен».
     */
    @Override
    public TelegramLink createTelegramLink(Actor actor) {
        var accountId = requireAccount(actor);
        var telegram = properties.telegram();
        if (!telegram.configured()) {
            throw new TelegramUnavailableException();
        }
        var issued = linkTokens.issue(accountId);
        return new TelegramLink(
                URI.create("https://t.me/" + telegram.botUsername() + "?start=" + issued.token()), issued.expiresAt());
    }

    @Override
    public void unlinkTelegram(Actor actor) {
        chats.deleteByAccountId(requireAccount(actor));
    }

    /**
     * Почта доступна, если она настроена на сервере и у карточки аккаунта есть адрес (спека §3.2); Telegram — если
     * настроен бот (задан токен): то же условие, что у канала Telegram при отправке.
     */
    private MyChannels channels(UUID accountId) {
        var switches = preferences.findSwitches(accountId).orElseGet(() -> defaults.forAccount(accountId));
        var emailAvailable = mailServer.configured()
                && clients.byAccountId(accountId)
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
