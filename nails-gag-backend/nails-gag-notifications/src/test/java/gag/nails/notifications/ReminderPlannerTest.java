package gag.nails.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.booking.EarlierTimeOffers;
import gag.nails.booking.VisitMarks;
import gag.nails.booking.event.BookingConfirmed;
import gag.nails.booking.event.BookingCreated;
import gag.nails.booking.model.BookingCreator;
import gag.nails.booking.model.BookingStatus;
import gag.nails.booking.model.BookingView;
import gag.nails.common.actor.Actor;
import gag.nails.notifications.command.UpdateReminderSettings;
import gag.nails.notifications.model.MessageKind;
import gag.nails.notifications.model.MessageStatus;
import gag.nails.notifications.model.ReminderSettings;
import gag.nails.testsupport.TestTimeConfiguration;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Напоминания о визите (спека §3.4) — настоящими операциями модуля записей и настройками напоминаний. Настройки по
 * умолчанию — накануне в 12:00 и за 2 часа; сейчас — четверг 1 октября 2026, 9:00 по Москве. Тест, меняющий
 * настройки, возвращает их как были (мимо журнала) и удаляет свои строки журнала.
 */
@NotificationsModuleTest
class ReminderPlannerTest extends QueueTestSupport {

    private static final Instant NOW = TestTimeConfiguration.START;

    /** Понедельник 5 октября, 14:00. */
    private static final Instant MONDAY_14 = Instant.parse("2026-10-05T11:00:00Z");

    /** Воскресенье 4 октября, 12:00. */
    private static final Instant SUNDAY_NOON = Instant.parse("2026-10-04T09:00:00Z");

    /** Понедельник 5 октября, 12:00. */
    private static final Instant MONDAY_NOON = Instant.parse("2026-10-05T09:00:00Z");

    @Autowired
    NotificationSettings settings;

    @Autowired
    VisitMarks marks;

    @Autowired
    EarlierTimeOffers offers;

    private final Actor settingsEditor = Actor.of(UUID.randomUUID(), null, Actor.masterAccess());

    private ReminderSettings original;

    @BeforeEach
    void rememberSettings() {
        original = settings.settings(master);
        restoreDefaults();
    }

    @AfterEach
    void restoreSettings() {
        jdbc.sql("update notifications.settings set reminder_day_before_at = ?, reminder_hours_before = ?")
                .params(
                        original.dayBeforeAt() == null ? null : Time.valueOf(original.dayBeforeAt()),
                        original.hoursBefore())
                .update();
        jdbc.sql("delete from notifications.settings_change where changed_by = ?")
                .param(settingsEditor.accountId())
                .update();
    }

    @Test
    void newBookingGetsBothRemindersForItsClient() {
        var anna = client("Анна");

        var booking = bookByClient(anna, slot(MONDAY, "14:00"));

        assertThat(reminders(booking.id()))
                .containsExactlyInAnyOrder(
                        pending(MessageKind.REMINDER_DAY_BEFORE, anna, SUNDAY_NOON, MONDAY_14),
                        pending(MessageKind.REMINDER_HOURS_BEFORE, anna, MONDAY_NOON, MONDAY_14));
        assertThat(dedupKeys(booking.id()))
                .contains(
                        "reminder:REMINDER_DAY_BEFORE:" + booking.id() + ":2026-10-04T09:00:00Z",
                        "reminder:REMINDER_HOURS_BEFORE:" + booking.id() + ":2026-10-05T09:00:00Z");
    }

    @Test
    void bookingAwaitingPrepaymentGetsRemindersToo() {
        var vera = client("Вера");

        var booking = bookByMaster(vera, slot(MONDAY, "14:00"), true);

        assertThat(booking.status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);
        assertThat(reminders(booking.id()))
                .extracting(QueuedMessage::kind)
                .containsExactlyInAnyOrder(MessageKind.REMINDER_DAY_BEFORE, MessageKind.REMINDER_HOURS_BEFORE);
    }

    @Test
    void rescheduleReplacesPendingReminders() {
        var anna = client("Анна");
        var monday = slot(MONDAY, "14:00");
        var booking = bookByClient(anna, monday);
        var tuesday = slot(TUESDAY, "16:00");
        var tuesday16 = Instant.parse("2026-10-06T13:00:00Z");

        processed(bookings.reschedule(booking.id(), tuesday.id(), master));

        // Прежние неотправленные удалены, а не отменены: их ключи свободны для возврата на прежнее время.
        assertThat(reminders(booking.id()))
                .containsExactlyInAnyOrder(
                        pending(MessageKind.REMINDER_DAY_BEFORE, anna, MONDAY_NOON, tuesday16),
                        pending(
                                MessageKind.REMINDER_HOURS_BEFORE,
                                anna,
                                Instant.parse("2026-10-06T11:00:00Z"),
                                tuesday16));

        // Обратно на прежний слот: те же ключи повтора — напоминания создаются снова.
        processed(bookings.reschedule(booking.id(), monday.id(), master));

        assertThat(reminders(booking.id()))
                .containsExactlyInAnyOrder(
                        pending(MessageKind.REMINDER_DAY_BEFORE, anna, SUNDAY_NOON, MONDAY_14),
                        pending(MessageKind.REMINDER_HOURS_BEFORE, anna, MONDAY_NOON, MONDAY_14));
    }

    @Test
    void cancellationCancelsPendingReminders() {
        var anna = client("Анна");
        var booking = bookByClient(anna, slot(MONDAY, "14:00"));

        processed(bookings.cancel(booking.id(), Actor.client(anna)));

        assertThat(reminders(booking.id()))
                .hasSize(2)
                .allSatisfy(reminder -> assertThat(reminder.status()).isEqualTo(MessageStatus.CANCELLED));
    }

    @Test
    void completionCancelsPendingReminders() {
        var anna = client("Анна");
        var booking = bookByClient(anna, slot(MONDAY, "14:00"));
        clock.set(MONDAY_14.plus(Duration.ofHours(2)));

        processed(marks.complete(booking.id(), master));

        assertThat(reminders(booking.id()))
                .hasSize(2)
                .allSatisfy(reminder -> assertThat(reminder.status()).isEqualTo(MessageStatus.CANCELLED));
    }

    @Test
    void noShowCancelsPendingReminders() {
        var anna = client("Анна");
        var booking = bookByClient(anna, slot(MONDAY, "14:00"));
        clock.set(MONDAY_14.plus(Duration.ofHours(2)));

        processed(marks.markNoShow(booking.id(), master));

        assertThat(reminders(booking.id()))
                .hasSize(2)
                .allSatisfy(reminder -> assertThat(reminder.status()).isEqualTo(MessageStatus.CANCELLED));
    }

    @Test
    void settingsChangeReplansFutureBookings() {
        var anna = client("Анна");
        var maria = client("Мария");
        var monday = bookByClient(anna, slot(MONDAY, "14:00"));
        var tuesday = bookByClient(maria, slot(TUESDAY, "10:00"));
        var tuesday10 = Instant.parse("2026-10-06T07:00:00Z");

        settings.update(new UpdateReminderSettings(LocalTime.of(18, 30), 3), settingsEditor);

        assertThat(reminders(monday.id()))
                .containsExactlyInAnyOrder(
                        pending(
                                MessageKind.REMINDER_DAY_BEFORE,
                                anna,
                                Instant.parse("2026-10-04T15:30:00Z"),
                                MONDAY_14),
                        pending(
                                MessageKind.REMINDER_HOURS_BEFORE,
                                anna,
                                Instant.parse("2026-10-05T08:00:00Z"),
                                MONDAY_14));
        // Вторник 10:00: за 3 часа — 7:00, тихие часы → 9:00.
        assertThat(reminders(tuesday.id()))
                .containsExactlyInAnyOrder(
                        pending(
                                MessageKind.REMINDER_DAY_BEFORE,
                                maria,
                                Instant.parse("2026-10-05T15:30:00Z"),
                                tuesday10),
                        pending(
                                MessageKind.REMINDER_HOURS_BEFORE,
                                maria,
                                Instant.parse("2026-10-06T06:00:00Z"),
                                tuesday10));
    }

    @Test
    void settingsChangeLeavesFinishedBookingsAlone() {
        var anna = client("Анна");
        var booking = bookByClient(anna, slot(MONDAY, "14:00"));
        processed(bookings.cancel(booking.id(), Actor.client(anna)));

        settings.update(new UpdateReminderSettings(LocalTime.of(18, 30), 3), settingsEditor);

        assertThat(reminders(booking.id()))
                .hasSize(2)
                .allSatisfy(reminder -> assertThat(reminder.status()).isEqualTo(MessageStatus.CANCELLED));
    }

    @Test
    void turningReminderOffRemovesItsPendingAndTurningOnRestoresThem() {
        var anna = client("Анна");
        var booking = bookByClient(anna, slot(MONDAY, "14:00"));

        settings.update(new UpdateReminderSettings(null, 2), settingsEditor);

        assertThat(reminders(booking.id()))
                .containsExactly(pending(MessageKind.REMINDER_HOURS_BEFORE, anna, MONDAY_NOON, MONDAY_14));

        settings.update(new UpdateReminderSettings(LocalTime.NOON, 2), settingsEditor);

        assertThat(reminders(booking.id()))
                .containsExactlyInAnyOrder(
                        pending(MessageKind.REMINDER_DAY_BEFORE, anna, SUNDAY_NOON, MONDAY_14),
                        pending(MessageKind.REMINDER_HOURS_BEFORE, anna, MONDAY_NOON, MONDAY_14));

        settings.update(new UpdateReminderSettings(null, null), settingsEditor);

        assertThat(reminders(booking.id())).isEmpty();
    }

    @Test
    void replayedEventCreatesNoDuplicates() {
        var anna = client("Анна");
        var booking = bookByClient(anna, slot(MONDAY, "14:00"));
        var before = reminders(booking.id());

        publish(booking.id(), created(booking));
        publish(booking.id(), new BookingConfirmed(booking.id(), anna, booking.slotStartsAt(), false, NOW));

        assertThat(before).hasSize(2);
        assertThat(reminders(booking.id())).containsExactlyInAnyOrderElementsOf(before);
    }

    @Test
    void creationProcessedAfterCancellationPlansNothing() {
        var anna = client("Анна");
        var booking = bookByClient(anna, slot(MONDAY, "14:00"));
        processed(bookings.cancel(booking.id(), Actor.client(anna)));

        // События пришли не по порядку: создание обрабатывается уже после отмены.
        publish(booking.id(), created(booking));

        assertThat(reminders(booking.id()))
                .hasSize(2)
                .allSatisfy(reminder -> assertThat(reminder.status()).isEqualTo(MessageStatus.CANCELLED));
    }

    @Test
    void eventOfMissingBookingPlansNothing() {
        var bookingId = bookingId();
        var clientId = UUID.randomUUID();

        publish(
                bookingId,
                new BookingCreated(
                        bookingId, clientId, MONDAY_14, BookingStatus.CONFIRMED, BookingCreator.CLIENT, NOW));

        assertThat(reminders(bookingId)).isEmpty();
    }

    @Test
    void eventOfMissingBookingDeletesItsPendingReminders() {
        var bookingId = bookingId();
        var clientId = UUID.randomUUID();
        jdbc.sql("""
                        insert into notifications.message (id, client_id, kind, booking_id, due_at, expires_at, status,
                            dedup_key, created_at)
                        values (:id, :clientId, 'REMINDER_DAY_BEFORE', :bookingId, :dueAt, :expiresAt, 'PENDING',
                            :dedupKey, :now)
                        """)
                .param("id", UUID.randomUUID())
                .param("clientId", clientId)
                .param("bookingId", bookingId)
                .param("dueAt", Timestamp.from(SUNDAY_NOON))
                .param("expiresAt", Timestamp.from(MONDAY_14))
                .param("dedupKey", "reminder:REMINDER_DAY_BEFORE:" + bookingId + ":" + SUNDAY_NOON)
                .param("now", Timestamp.from(NOW))
                .update();

        publish(bookingId, new BookingConfirmed(bookingId, clientId, MONDAY_14, false, NOW));

        assertThat(reminders(bookingId)).isEmpty();
    }

    @Test
    void erasedClientGetsNoReminders() {
        var anna = client("Анна");
        var booking = bookByClient(anna, slot(MONDAY, "14:00"));

        // Обезличивание отменяет неотправленные; запись остаётся активной — на рассмотрении у мастера.
        clients.erase(anna);
        settings.update(new UpdateReminderSettings(LocalTime.of(18, 30), 3), settingsEditor);
        publish(booking.id(), new BookingConfirmed(booking.id(), anna, booking.slotStartsAt(), false, NOW));

        assertThat(reminders(booking.id()))
                .hasSize(2)
                .allSatisfy(reminder -> assertThat(reminder.status()).isEqualTo(MessageStatus.CANCELLED));
    }

    @Test
    void acceptedEarlierTimeReplansFromAgreedStart() {
        var anna = client("Анна");
        var booking = bookByClient(anna, slot(MONDAY, "14:00"));
        var agreed = Instant.parse("2026-10-05T08:00:00Z");
        processed(offers.propose(booking.id(), agreed, master));

        processed(offers.respond(booking.id(), true, Actor.client(anna)));

        // Согласовано 11:00: за 2 часа — 9:00, оба живут до 11:00.
        assertThat(reminders(booking.id()))
                .containsExactlyInAnyOrder(
                        pending(MessageKind.REMINDER_DAY_BEFORE, anna, SUNDAY_NOON, agreed),
                        pending(
                                MessageKind.REMINDER_HOURS_BEFORE,
                                anna,
                                Instant.parse("2026-10-05T06:00:00Z"),
                                agreed));
    }

    /** Умолчания миграции: тест не зависит от того, что оставили в строке настроек другие. */
    private void restoreDefaults() {
        jdbc.sql("update notifications.settings set reminder_day_before_at = '12:00', reminder_hours_before = 2")
                .update();
    }

    private static BookingCreated created(BookingView booking) {
        return new BookingCreated(
                booking.id(), booking.clientId(), booking.slotStartsAt(), booking.status(), booking.createdBy(), NOW);
    }

    private List<QueuedMessage> reminders(UUID bookingId) {
        return jdbc.sql("""
                        select kind, client_id, account_id, due_at, expires_at, status from notifications.message
                        where booking_id = :bookingId and kind like 'REMINDER\\_%'
                        """)
                .param("bookingId", bookingId)
                .query(ReminderPlannerTest::reminder)
                .list();
    }

    private static QueuedMessage pending(MessageKind kind, UUID clientId, Instant dueAt, Instant expiresAt) {
        return new QueuedMessage(kind, clientId, null, dueAt, expiresAt, MessageStatus.PENDING);
    }

    private static QueuedMessage reminder(ResultSet row, int rowNumber) throws SQLException {
        return new QueuedMessage(
                MessageKind.valueOf(row.getString("kind")),
                row.getObject("client_id", UUID.class),
                row.getObject("account_id", UUID.class),
                row.getTimestamp("due_at").toInstant(),
                row.getTimestamp("expires_at").toInstant(),
                MessageStatus.valueOf(row.getString("status")));
    }
}
