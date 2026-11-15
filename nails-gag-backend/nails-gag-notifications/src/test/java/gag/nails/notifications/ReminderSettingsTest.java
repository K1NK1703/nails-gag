package gag.nails.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.common.paging.Paging;
import gag.nails.notifications.command.DeliveryFilter;
import gag.nails.notifications.command.UpdateReminderSettings;
import gag.nails.notifications.model.ReminderSettings;
import gag.nails.notifications.model.ReminderSettingsChange;
import gag.nails.testsupport.MutableClock;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Настройки напоминаний: умолчания, журнал, проверки и права. Тихие часы — умолчание настроек модуля, 22:00–09:00.
 * Каждый тест в транзакции с откатом: единственная строка настроек после теста прежняя.
 */
@NotificationsModuleTest
@Transactional
class ReminderSettingsTest {

    private static final LocalTime NOON = LocalTime.of(12, 0);

    @Autowired
    NotificationSettings settings;

    @Autowired
    MutableClock clock;

    @Test
    void defaultsAreNoonOnTheDayBeforeAndTwoHours() {
        assertThat(settings.settings(Actor.master())).isEqualTo(new ReminderSettings(NOON, 2));
    }

    @Test
    void changedFieldsAreJournaledWithAccount() {
        var master = staff(Actor.masterAccess());

        var updated = settings.update(new UpdateReminderSettings(LocalTime.of(13, 30), 3), master);

        assertThat(updated).isEqualTo(new ReminderSettings(LocalTime.of(13, 30), 3));
        assertThat(settings.settings(master)).isEqualTo(updated);
        assertThat(journalOf(master))
                .containsExactlyInAnyOrder(
                        new ReminderSettingsChange(
                                "reminder_day_before_at", "12:00", "13:30", master.accountId(), clock.instant()),
                        new ReminderSettingsChange(
                                "reminder_hours_before", "2", "3", master.accountId(), clock.instant()));
    }

    @Test
    void unchangedFieldIsNotJournaled() {
        var master = staff(Actor.masterAccess());

        settings.update(new UpdateReminderSettings(NOON, 4), master);

        assertThat(journalOf(master))
                .containsExactly(new ReminderSettingsChange(
                        "reminder_hours_before", "2", "4", master.accountId(), clock.instant()));
    }

    @Test
    void historyIsNewestFirst() {
        var master = staff(Actor.masterAccess());
        settings.update(new UpdateReminderSettings(NOON, 3), master);
        clock.advance(Duration.ofMinutes(1));
        settings.update(new UpdateReminderSettings(NOON, 5), master);

        assertThat(journalOf(master))
                .extracting(ReminderSettingsChange::newValue)
                .containsExactly("5", "3");
    }

    @Test
    void historyWithEqualTimesIsOrderedById() {
        var master = staff(Actor.masterAccess());
        settings.update(new UpdateReminderSettings(NOON, 3), master);
        settings.update(new UpdateReminderSettings(NOON, 5), master);

        assertThat(journalOf(master))
                .allSatisfy(change -> assertThat(change.changedAt()).isEqualTo(clock.instant()))
                .extracting(ReminderSettingsChange::newValue)
                .containsExactly("5", "3");
    }

    @Test
    void nullsTurnRemindersOff() {
        var master = staff(Actor.masterAccess());

        var updated = settings.update(new UpdateReminderSettings(null, null), master);

        assertThat(updated).isEqualTo(new ReminderSettings(null, null));
        assertThat(settings.settings(master)).isEqualTo(new ReminderSettings(null, null));
        assertThat(journalOf(master))
                .containsExactlyInAnyOrder(
                        new ReminderSettingsChange(
                                "reminder_day_before_at", "12:00", null, master.accountId(), clock.instant()),
                        new ReminderSettingsChange(
                                "reminder_hours_before", "2", null, master.accountId(), clock.instant()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"22:00", "23:30", "00:00", "03:00", "08:59"})
    void dayBeforeAtInQuietHoursIsRejected(String time) {
        var command = new UpdateReminderSettings(LocalTime.parse(time), 2);

        assertThatThrownBy(() -> settings.update(command, Actor.master()))
                .isInstanceOfSatisfying(
                        ValidationException.class, e -> assertThat(e.field()).isEqualTo("dayBeforeAt"));
        assertThat(settings.settings(Actor.master())).isEqualTo(new ReminderSettings(NOON, 2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"09:00", "21:59"})
    void dayBeforeAtAtQuietHoursEdgesIsAccepted(String time) {
        var updated = settings.update(new UpdateReminderSettings(LocalTime.parse(time), 2), Actor.master());

        assertThat(updated.dayBeforeAt()).isEqualTo(LocalTime.parse(time));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12:00:30", "12:00:00.000000001", "12:00:00.5"})
    void dayBeforeAtMustBeWholeMinutes(String time) {
        var command = new UpdateReminderSettings(LocalTime.parse(time), 2);

        assertThatThrownBy(() -> settings.update(command, Actor.master()))
                .isInstanceOfSatisfying(
                        ValidationException.class, e -> assertThat(e.field()).isEqualTo("dayBeforeAt"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 25})
    void hoursBeforeOutsideOneToTwentyFourIsRejected(int hours) {
        var command = new UpdateReminderSettings(NOON, hours);

        assertThatThrownBy(() -> settings.update(command, Actor.master()))
                .isInstanceOfSatisfying(
                        ValidationException.class, e -> assertThat(e.field()).isEqualTo("hoursBefore"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 24})
    void hoursBeforeBoundsAreAccepted(int hours) {
        assertThat(settings.update(new UpdateReminderSettings(NOON, hours), Actor.master())
                        .hoursBefore())
                .isEqualTo(hours);
    }

    @Test
    void developerWrites() {
        var developer = staff(Actor.developerAccess());

        settings.update(new UpdateReminderSettings(LocalTime.of(10, 0), 1), developer);

        assertThat(settings.settings(developer)).isEqualTo(new ReminderSettings(LocalTime.of(10, 0), 1));
        assertThat(journalOf(developer)).hasSize(2);
    }

    @Test
    void readerReadsButDoesNotWrite() {
        var reader = staff(Map.of(Section.NOTIFICATIONS, AccessLevel.READ));

        assertThat(settings.settings(reader)).isEqualTo(new ReminderSettings(NOON, 2));
        assertThat(settings.history(reader)).isNotNull();
        assertThat(settings.deliveries(new DeliveryFilter(null, UUID.randomUUID()), new Paging(0, 20), reader)
                        .total())
                .isZero();
        assertThatThrownBy(() -> settings.update(new UpdateReminderSettings(NOON, 3), reader))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void clientIsDenied() {
        var client = Actor.of(UUID.randomUUID(), UUID.randomUUID(), Map.of());
        var paging = new Paging(0, 20);

        assertThatThrownBy(() -> settings.settings(client)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> settings.update(new UpdateReminderSettings(NOON, 3), client))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> settings.history(client)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> settings.deliveries(new DeliveryFilter(null, null), paging, client))
                .isInstanceOf(AccessDeniedException.class);
    }

    /** Сотрудник со своим аккаунтом: по нему журнал отбирает строки этого теста. */
    private static Actor staff(Map<Section, AccessLevel> access) {
        return Actor.of(UUID.randomUUID(), null, access);
    }

    private List<ReminderSettingsChange> journalOf(Actor actor) {
        return settings.history(Actor.master()).stream()
                .filter(change -> Objects.equals(actor.accountId(), change.changedBy()))
                .toList();
    }
}
