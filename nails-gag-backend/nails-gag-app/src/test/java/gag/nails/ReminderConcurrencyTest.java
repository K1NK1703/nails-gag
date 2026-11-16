package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import gag.nails.booking.BookingService;
import gag.nails.booking.command.CreateBooking;
import gag.nails.clients.ClientService;
import gag.nails.clients.command.CreateClient;
import gag.nails.clients.command.RecordConsent;
import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentSource;
import gag.nails.common.actor.Actor;
import gag.nails.common.time.SalonTimeZone;
import gag.nails.notifications.NotificationSettings;
import gag.nails.notifications.command.UpdateReminderSettings;
import gag.nails.notifications.internal.reminder.ReminderPlanner;
import gag.nails.notifications.model.ReminderSettings;
import gag.nails.schedule.ScheduleService;
import java.sql.Time;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
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
 * Изменения очереди встречаются со сменой настроек напоминаний на строке настроек: перепланирование и отмена
 * напоминаний записи, обезличивание карточки берут её на чтение ({@code FOR SHARE}) раньше строк очереди, смена — на
 * запись. Начатые во время смены, они ждут её фиксации и видят её напоминания. Без разделяемой блокировки
 * перепланирование прочитало бы прежние настройки и перезаписало бы напоминания смены прежними, а отмена и
 * обезличивание не увидели бы напоминаний, поставленных сменой (их запрос начался до её фиксации), — те остались бы
 * неотправленными; обезличивание к тому же могло встать во взаимную блокировку со сменой на строках очереди. Тест
 * возвращает настройки как были (мимо журнала) и удаляет свои записи, слоты, карточки, сообщения и строки журнала.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE, properties = "nails-gag.booking.jobs.enabled=false")
@Import(PostgresTestcontainers.class)
class ReminderConcurrencyTest {

    private static final UUID MANICURE = UUID.fromString("019a0c00-0000-7000-8000-000000000001");
    private static final String DOCUMENTS_VERSION = "2026-09-25";
    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired
    NotificationSettings settings;

    @Autowired
    ReminderPlanner planner;

    @Autowired
    BookingService bookings;

    @Autowired
    ClientService clients;

    @Autowired
    ScheduleService schedule;

    @Autowired
    SalonTimeZone salonTimeZone;

    @Autowired
    Clock clock;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcClient jdbc;

    private final Actor master = Actor.master();
    private final Actor editor = Actor.of(UUID.randomUUID(), null, Actor.masterAccess());
    private final List<UUID> bookingIds = new ArrayList<>();
    private final List<UUID> clientIds = new ArrayList<>();
    private final List<UUID> slotIds = new ArrayList<>();

    private ConcurrencySupport locks;
    private ReminderSettings original;

    @BeforeEach
    void rememberSettings() {
        locks = new ConcurrencySupport(transaction, jdbc, "%notifications.%");
        original = settings.settings(master);
    }

    @AfterEach
    void restore() {
        jdbc.sql("update notifications.settings set reminder_day_before_at = ?, reminder_hours_before = ?")
                .params(
                        original.dayBeforeAt() == null ? null : Time.valueOf(original.dayBeforeAt()),
                        original.hoursBefore())
                .update();
        jdbc.sql("delete from notifications.settings_change where changed_by = ?")
                .param(editor.accountId())
                .update();
        if (!bookingIds.isEmpty()) {
            delete("delete from notifications.message where booking_id in (:ids)", bookingIds);
            delete("delete from booking.booking_service where booking_id in (:ids)", bookingIds);
            delete("delete from booking.booking where id in (:ids)", bookingIds);
        }
        if (!clientIds.isEmpty()) {
            delete("delete from booking.client_lock where id in (:ids)", clientIds);
            delete("delete from clients.consent where client_id in (:ids)", clientIds);
            delete("delete from clients.prepayment_flag_change where client_id in (:ids)", clientIds);
            delete("delete from clients.client where id in (:ids)", clientIds);
        }
        if (!slotIds.isEmpty()) {
            delete("delete from schedule.slot where id in (:ids)", slotIds);
        }
    }

    @Test
    void replanDuringSettingsUpdateWaitsAndUsesNewSettings() throws Exception {
        var day = LocalDate.now(clock.withZone(salonTimeZone.zone())).plusDays(41);
        var bookingId = booking(day, LocalTime.of(15, 0));
        var changed = new UpdateReminderSettings(LocalTime.of(19, 47), 5);

        var replanned = locks.whileRowIsHeld(
                () -> settings.update(changed, editor),
                () -> transaction.executeWithoutResult(status -> planner.replan(bookingId)));

        assertThat(replanned.failure()).isNull();
        assertThat(reminders(bookingId))
                .containsExactlyInAnyOrder(
                        tuple("REMINDER_DAY_BEFORE", salonTimeZone.at(day.minusDays(1), LocalTime.of(19, 47))),
                        tuple("REMINDER_HOURS_BEFORE", salonTimeZone.at(day, LocalTime.of(10, 0))));
    }

    @Test
    void cancelDuringSettingsUpdateWaitsAndCancelsItsReminders() throws Exception {
        var day = LocalDate.now(clock.withZone(salonTimeZone.zone())).plusDays(42);
        var bookingId = booking(day, LocalTime.of(15, 0));

        var cancelled = locks.whileRowIsHeld(
                () -> settings.update(new UpdateReminderSettings(LocalTime.of(19, 47), 5), editor),
                () -> transaction.executeWithoutResult(status -> planner.cancel(bookingId)));

        assertThat(cancelled.failure()).isNull();
        assertThat(statuses(bookingId)).isNotEmpty().containsOnly("CANCELLED");
    }

    @Test
    void erasureDuringSettingsUpdateWaitsAndCancelsItsReminders() throws Exception {
        var day = LocalDate.now(clock.withZone(salonTimeZone.zone())).plusDays(43);
        var bookingId = booking(day, LocalTime.of(15, 0));
        var clientId = clientIds.getLast();

        var erased = locks.whileRowIsHeld(
                () -> settings.update(new UpdateReminderSettings(LocalTime.of(19, 47), 5), editor),
                () -> clients.erase(clientId));

        assertThat(erased.failure()).isNull();
        assertThat(statuses(bookingId)).isNotEmpty().containsOnly("CANCELLED");
    }

    /** Запись мастером на {@code day} в {@code time}; ждёт, пока слушатели уведомлений не обработают её создание. */
    private UUID booking(LocalDate day, LocalTime time) {
        var clientId = clients.create(new CreateClient("Анна", null, null, null, null, null), master)
                .id();
        clientIds.add(clientId);
        for (var kind : List.of(ConsentKind.PERSONAL_DATA, ConsentKind.OFFER)) {
            clients.recordConsent(
                    clientId, new RecordConsent(kind, DOCUMENTS_VERSION, ConsentSource.ENTERED_BY_MASTER), master);
        }
        var slot = schedule.addSlot(day, time, master);
        slotIds.add(slot.id());
        var booking = bookings.createByMaster(
                new CreateBooking(clientId, slot.id(), MANICURE, List.of(), null), false, master);
        bookingIds.add(booking.id());
        awaitProcessed(booking.id());
        return booking.id();
    }

    /** Ждёт, пока в реестре публикаций не останется событий о записи. */
    private void awaitProcessed(UUID bookingId) {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (true) {
            var statuses = jdbc.sql("""
                            select coalesce(status, 'NULL') from modulith.event_publication
                            where serialized_event like :pattern
                            """)
                    .param("pattern", "%" + bookingId + "%")
                    .query(String.class)
                    .list();
            if (statuses.isEmpty()) {
                return;
            }
            if (statuses.contains("FAILED") || System.nanoTime() > deadline) {
                throw new AssertionError("publications of " + bookingId + " are not completed: " + statuses);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** Неотправленные напоминания записи: вид и срок отправки. */
    private List<Tuple> reminders(UUID bookingId) {
        return jdbc.sql("""
                        select kind, due_at from notifications.message
                        where booking_id = ? and kind like 'REMINDER\\_%' and status = 'PENDING'
                        """)
                .param(bookingId)
                .query((row, rowNumber) ->
                        tuple(row.getString("kind"), row.getTimestamp("due_at").toInstant()))
                .list();
    }

    /** Состояния всех напоминаний записи. */
    private List<String> statuses(UUID bookingId) {
        return jdbc.sql("select status from notifications.message where booking_id = ? and kind like 'REMINDER\\_%'")
                .param(bookingId)
                .query(String.class)
                .list();
    }

    private void delete(String sql, List<UUID> ids) {
        jdbc.sql(sql).param("ids", ids).update();
    }
}
