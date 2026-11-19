package gag.nails.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.ClientService;
import gag.nails.clients.command.RegisterClient;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.identity.AccessAdministration;
import gag.nails.identity.command.CreateRole;
import gag.nails.identity.error.AccountNotFoundException;
import gag.nails.identity.model.RoleView;
import gag.nails.notifications.command.UpdateMyChannels;
import gag.nails.notifications.error.TelegramUnavailableException;
import gag.nails.notifications.model.MyChannels;
import gag.nails.testsupport.MutableClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Свои каналы аккаунта: умолчания по роли, доступность почты и Telegram, сохранение настройки. Бот в тестах не
 * настроен. Аккаунты сотрудников — строки {@code identity.account} с ролью; клиентки — карточки с аккаунтом. Каждый
 * тест в транзакции с откатом.
 */
@NotificationsModuleTest
@Transactional
class MyChannelsTest {

    @Autowired
    NotificationPreferences preferences;

    @Autowired
    ClientService clients;

    @Autowired
    AccessAdministration access;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Test
    void clientWithoutPreferenceHasEmailAndTelegram() {
        var client = client("anna@example.com");

        assertThat(preferences.mine(client)).isEqualTo(new MyChannels(true, true, true, false, false));
    }

    @Test
    void emailIsAvailableOnlyWithCardEmail() {
        var withoutEmail = client(null);
        var withoutCard = Actor.of(UUID.randomUUID(), null, Map.of());

        assertThat(preferences.mine(withoutEmail).emailAvailable()).isFalse();
        assertThat(preferences.mine(withoutCard).emailAvailable()).isFalse();
        assertThat(preferences.mine(withoutCard)).isEqualTo(new MyChannels(true, false, true, false, false));
    }

    @Test
    void masterDefaultsToTelegramOnly() {
        var master = staff("MASTER", Actor.masterAccess());

        assertThat(preferences.mine(master)).isEqualTo(new MyChannels(false, false, true, false, false));
    }

    @Test
    void developerDefaultsToNothing() {
        var developer = staff("DEVELOPER", Actor.developerAccess());

        assertThat(preferences.mine(developer)).isEqualTo(new MyChannels(false, false, false, false, false));
    }

    @Test
    void staffWithoutBookingsReadKeepsClientDefaults() {
        // Мастерские сообщения получают только читающие записи; остальным аккаунтам — клиентские умолчания.
        var accountant = Map.of(Section.FINANCE, AccessLevel.READ);
        var role = access.createRole(new CreateRole("ACCOUNTANT", "Бухгалтер", accountant), Actor.developer());
        var accountId = account();
        access.assignRole(accountId, role.id(), Actor.developer());
        var actor = Actor.of(accountId, null, accountant);

        assertThat(preferences.mine(actor)).isEqualTo(new MyChannels(true, false, true, false, false));
    }

    @Test
    void updatePersistsAndReplacesDefaults() {
        var client = client("olga@example.com");
        var master = staff("MASTER", Actor.masterAccess());

        var clientChannels = preferences.update(new UpdateMyChannels(false, true), client);
        preferences.update(new UpdateMyChannels(true, true), master);
        clock.advance(Duration.ofMinutes(1));
        var masterChannels = preferences.update(new UpdateMyChannels(true, false), master);

        assertThat(clientChannels).isEqualTo(new MyChannels(false, true, true, false, false));
        assertThat(preferences.mine(client)).isEqualTo(clientChannels);
        assertThat(masterChannels).isEqualTo(new MyChannels(true, false, false, false, false));
        assertThat(preferences.mine(master)).isEqualTo(masterChannels);
        assertThat(jdbc.sql("select count(*) from notifications.preference where account_id = ?")
                        .param(master.accountId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    void linkedChatIsShownAndUnlinkIsIdempotent() {
        var client = client("vera@example.com");
        jdbc.sql("insert into notifications.telegram_chat (id, account_id, chat_id, linked_at) values (?, ?, ?, ?)")
                .params(
                        UUID.randomUUID(),
                        client.accountId(),
                        ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE),
                        Timestamp.from(clock.instant()))
                .update();

        assertThat(preferences.mine(client).telegramLinked()).isTrue();

        preferences.unlinkTelegram(client);
        preferences.unlinkTelegram(client);

        assertThat(preferences.mine(client).telegramLinked()).isFalse();
    }

    @Test
    void telegramLinkIsUnavailableWithoutBot() {
        var client = client("irina@example.com");

        assertThat(preferences.mine(client).telegramAvailable()).isFalse();
        assertThatThrownBy(() -> preferences.createTelegramLink(client))
                .isInstanceOfSatisfying(
                        TelegramUnavailableException.class,
                        e -> assertThat(e.code()).isEqualTo("notifications.telegram_unavailable"));
    }

    /** Аккаунта в {@code identity} нет (удалён) — своих каналов у него не бывает. */
    @Test
    void deletedAccountCannotUpdateChannels() {
        var gone = Actor.of(UUID.randomUUID(), null, Map.of());

        assertThatThrownBy(() -> preferences.update(new UpdateMyChannels(false, true), gone))
                .isInstanceOf(AccountNotFoundException.class);
        assertThat(jdbc.sql("select count(*) from notifications.preference where account_id = ?")
                        .param(gone.accountId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void actorWithoutAccountIsRejected() {
        var cardOnly = Actor.client(UUID.randomUUID());
        var builtIn = Actor.master();

        for (var actor : new Actor[] {cardOnly, builtIn}) {
            assertThatThrownBy(() -> preferences.mine(actor)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> preferences.update(new UpdateMyChannels(true, true), actor))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> preferences.createTelegramLink(actor)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> preferences.unlinkTelegram(actor)).isInstanceOf(AccessDeniedException.class);
        }
    }

    /**
     * Почта не настроена на сервере (спека §3.2): здесь нет отправителя — почта недоступна, даже если у карточки она
     * есть; включённой она остаётся. Свой контекст: зависимости — свои, не внешнего класса.
     */
    @Nested
    @TestPropertySource(properties = "nails-gag.notifications.email.from=")
    class WithoutMailServer {

        @Autowired
        NotificationPreferences withoutMail;

        @Autowired
        ClientService cards;

        @Autowired
        JdbcClient rows;

        @Test
        void emailIsUnavailableWithoutMailServer() {
            var accountId = UUID.randomUUID();
            rows.sql("insert into identity.account (id, created_at, last_login_at) values (?, now(), now())")
                    .param(accountId)
                    .update();
            var card = cards.register(new RegisterClient(accountId, "Анна", null, accountId + ".anna@example.com"));
            var client = Actor.of(accountId, card.id(), Map.of());

            assertThat(withoutMail.mine(client)).isEqualTo(new MyChannels(true, false, true, false, false));
            assertThat(withoutMail.update(new UpdateMyChannels(true, false), client))
                    .isEqualTo(new MyChannels(true, false, false, false, false));
        }
    }

    /** Клиентка: карточка, заведённая регистрацией, с аккаунтом. */
    private Actor client(@Nullable String email) {
        var accountId = account();
        var unique = email == null ? null : accountId + "." + email;
        var card = clients.register(new RegisterClient(accountId, "Анна", null, unique));
        return Actor.of(accountId, card.id(), Map.of());
    }

    /** Сотрудник без карточки клиента: аккаунт со встроенной ролью. */
    private Actor staff(String roleCode, Map<Section, AccessLevel> levels) {
        var accountId = account();
        access.assignRole(accountId, role(roleCode).id(), Actor.developer());
        return Actor.of(accountId, null, levels);
    }

    /** Аккаунт без карточки входом не создаётся (регистрация заводит карточку), поэтому строка — напрямую. */
    private UUID account() {
        var accountId = UUID.randomUUID();
        jdbc.sql("insert into identity.account (id, created_at, last_login_at) values (?, ?, ?)")
                .params(accountId, Timestamp.from(clock.instant()), Timestamp.from(clock.instant()))
                .update();
        return accountId;
    }

    private RoleView role(String code) {
        return access.roles(Actor.developer()).stream()
                .filter(role -> role.code().equals(code))
                .findFirst()
                .orElseThrow();
    }
}
