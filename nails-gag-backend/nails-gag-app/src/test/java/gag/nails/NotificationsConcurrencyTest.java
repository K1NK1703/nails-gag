package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import gag.nails.common.actor.Actor;
import gag.nails.notifications.NotificationPreferences;
import gag.nails.notifications.NotificationSettings;
import gag.nails.notifications.command.UpdateMyChannels;
import gag.nails.notifications.command.UpdateReminderSettings;
import gag.nails.notifications.model.MyChannels;
import gag.nails.notifications.model.ReminderSettings;
import gag.nails.notifications.model.ReminderSettingsChange;
import java.sql.Time;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Изменение настроек напоминаний встречается на блокировке единственной строки настроек, первое сохранение своих
 * каналов — на уникальном ключе аккаунта. Порядок задаётся ожиданием блокировки ({@link
 * ConcurrencySupport#whileRowIsHeld}): второй гарантированно ждёт первого. База общая для тестов с тем же контекстом:
 * тест возвращает настройки напоминаний как были (мимо журнала) и удаляет свои строки журнала и каналов.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE, properties = "nails-gag.booking.jobs.enabled=false")
@Import(PostgresTestcontainers.class)
class NotificationsConcurrencyTest {

    @Autowired
    NotificationSettings settings;

    @Autowired
    NotificationPreferences preferences;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcClient jdbc;

    private final Actor first = Actor.of(UUID.randomUUID(), null, Actor.masterAccess());
    private final Actor second = Actor.of(UUID.randomUUID(), null, Actor.developerAccess());

    private ConcurrencySupport locks;
    private ReminderSettings original;

    @BeforeEach
    void rememberSettings() {
        locks = new ConcurrencySupport(transaction, jdbc, "%notifications.%");
        original = settings.settings(first);
    }

    /** Убирает за собой: настройки — прежние, журнал и каналы аккаунтов теста — удалены. */
    @AfterEach
    void restore() {
        var accounts = List.of(first.accountId(), second.accountId());
        jdbc.sql("update notifications.settings set reminder_day_before_at = ?, reminder_hours_before = ?")
                .params(
                        original.dayBeforeAt() == null ? null : Time.valueOf(original.dayBeforeAt()),
                        original.hoursBefore())
                .update();
        jdbc.sql("delete from notifications.settings_change where changed_by in (:accounts)")
                .param("accounts", accounts)
                .update();
        jdbc.sql("delete from notifications.preference where account_id in (:accounts)")
                .param("accounts", accounts)
                .update();
    }

    @Test
    void settingsUpdateWaitingForAnotherJournalsItsValuesAsOld() throws Exception {
        var held = new UpdateReminderSettings(LocalTime.of(13, 17), 7);
        var waiting = new UpdateReminderSettings(LocalTime.of(14, 23), 9);

        var updated = locks.whileRowIsHeld(() -> settings.update(held, first), () -> settings.update(waiting, second));

        assertThat(updated.failure()).isNull();
        assertThat(settings.settings(first)).isEqualTo(new ReminderSettings(LocalTime.of(14, 23), 9));
        assertThat(journalOf(second))
                .extracting(ReminderSettingsChange::field, ReminderSettingsChange::oldValue)
                .containsExactlyInAnyOrder(
                        tuple("reminder_day_before_at", "13:17"), tuple("reminder_hours_before", "7"));
    }

    @Test
    void concurrentFirstChannelUpdatesBothSucceed() throws Exception {
        var updated = locks.whileRowIsHeld(
                () -> preferences.update(new UpdateMyChannels(true, true), first),
                () -> preferences.update(new UpdateMyChannels(false, true), first));

        assertThat(updated.failure()).isNull();
        assertThat(jdbc.sql("select count(*) from notifications.preference where account_id = ?")
                        .param(first.accountId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(preferences.mine(first)).isEqualTo(new MyChannels(false, false, true, false, false));
    }

    private List<ReminderSettingsChange> journalOf(Actor actor) {
        return settings.history(first).stream()
                .filter(change -> Objects.equals(actor.accountId(), change.changedBy()))
                .toList();
    }
}
