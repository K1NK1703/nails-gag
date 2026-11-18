package gag.nails.notifications.internal.preference;

import gag.nails.clients.ClientQueries;
import gag.nails.clients.model.ClientView;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.id.UuidV7;
import gag.nails.identity.Accounts;
import gag.nails.identity.error.AccountNotFoundException;
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
    private final Accounts accounts;
    private final ChannelDefaults defaults;
    private final ClientQueries clients;
    private final NotificationsProperties properties;
    private final MailServer mailServer;
    private final Clock clock;

    DefaultNotificationPreferences(
            PreferenceRepository preferences,
            TelegramChatRepository chats,
            LinkTokens linkTokens,
            Accounts accounts,
            ChannelDefaults defaults,
            ClientQueries clients,
            NotificationsProperties properties,
            MailServer mailServer,
            Clock clock) {
        this.preferences = preferences;
        this.chats = chats;
        this.linkTokens = linkTokens;
        this.accounts = accounts;
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

    /**
     * Строка аккаунта удерживается ({@link Accounts#lockExisting}) до записи: удаление аккаунта, начавшееся во
     * время записи, ждёт её фиксации и стирает настройку; удалённый раньше аккаунт настройки не получает
     * ({@link AccountNotFoundException}).
     */
    @Override
    public MyChannels update(UpdateMyChannels command, Actor actor) {
        var accountId = requireAccount(actor);
        if (!accounts.lockExisting(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
        preferences.upsert(
                UuidV7.next(), accountId, command.emailEnabled(), command.telegramEnabled(), clock.instant());
        return channels(accountId);
    }

    /**
     * Ссылка {@code https://t.me/<бот>?start=<токен>} с новым токеном аккаунта ({@link LinkTokens}). Актор без
     * аккаунта получает отказ первым, а не «бот недоступен». Перед записью токена строка аккаунта удерживается
     * ({@link Accounts#lockExisting}): удаление аккаунта, начавшееся во время записи, ждёт её фиксации и стирает
     * токен; удалённый раньше аккаунт ссылки не получает ({@link AccountNotFoundException}).
     */
    @Override
    public TelegramLink createTelegramLink(Actor actor) {
        var accountId = requireAccount(actor);
        var telegram = properties.telegram();
        if (!telegram.configured()) {
            throw new TelegramUnavailableException();
        }
        if (!accounts.lockExisting(accountId)) {
            throw new AccountNotFoundException(accountId);
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
