package gag.nails.notifications.internal.reminder;

import gag.nails.booking.BookingQueries;
import gag.nails.booking.model.BookingView;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.notifications.internal.queue.MessageQueue;
import gag.nails.notifications.internal.queue.NewMessage;
import gag.nails.notifications.internal.queue.QuietHours;
import gag.nails.notifications.internal.queue.Recipient;
import gag.nails.notifications.model.ReminderSettings;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Напоминания о визите в очереди (спека §3.4): перепланирование записи по её текущему состоянию и отмена, когда визита
 * не будет. Работает в транзакции вызывающего ({@code MANDATORY}) — слушателя события записи или смены настроек:
 * сбой откатывает и остальную работу этой транзакции, повтор безопасен благодаря ключам повтора.
 *
 * <p>Перепланирование удаляет неотправленные напоминания записи и рассчитывает их заново ({@link ReminderSchedule}) по
 * текущему {@link BookingView} — не по событию: события могут обрабатываться не по порядку и одновременно. Записи нет
 * — неотправленные напоминания удаляются; запись уже не активна (отменена, выполнена, не пришла) — отменяются, как на
 * событиях об этом; клиентка записи обезличена — неотправленные удаляются, новые не планируются (писать некуда);
 * исключений нет. Получатель — карточка клиента записи; ключ повтора — {@code reminder:<вид>:<запись>:<due_at>}.
 *
 * <p>Порядок блокировок очереди: строка настроек → запись ({@link ReminderLocks}) → строки очереди (в порядке id,
 * {@link MessageQueue}). Строку настроек первой берут все, кто меняет неотправленные сообщения: слушатели событий
 * записей и синхронные обработчики событий карточек и аккаунтов ({@code AccountEventsListener}) — на чтение
 * ({@code FOR SHARE}), смена настроек — на запись ({@code FOR UPDATE}). Обработчики событий карточек приходят с уже
 * взятыми строками своих модулей (карточки, записи), но смена настроек этих строк не ждёт — она только читает записи,
 * — поэтому встречного порядка нет. Без строки настроек слушатель, прочитавший прежние настройки до их смены, после её
 * фиксации перезаписал бы её напоминания прежними, отмена не увидела бы поставленных сменой напоминаний, а обработчик
 * карточки и смена настроек блокировали бы строки очереди накрест (взаимная блокировка).
 *
 * <p>Блокировка записи упорядочивает слушателей одной записи между собой: строку настроек на чтение они держат
 * одновременно. Смене настроек она не нужна: держа строку настроек на запись, смена исключает всех, кто берёт
 * блокировку записи, — они берут строку настроек раньше.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class ReminderPlanner {

    /** Системное чтение записей: планировщику нужна любая запись, без актора запроса. */
    private static final Actor BOOKINGS_READER = Actor.of(null, null, Map.of(Section.BOOKINGS, AccessLevel.READ));

    private final ReminderSettingsReader settings;
    private final ReminderLocks locks;
    private final BookingQueries bookings;
    private final MessageQueue queue;
    private final ReminderSchedule schedule;
    private final Clock clock;

    ReminderPlanner(
            ReminderSettingsReader settings,
            ReminderLocks locks,
            BookingQueries bookings,
            MessageQueue queue,
            QuietHours quietHours,
            Clock clock) {
        this.settings = settings;
        this.locks = locks;
        this.bookings = bookings;
        this.queue = queue;
        this.schedule = new ReminderSchedule(quietHours);
        this.clock = clock;
    }

    /** Рассчитывает напоминания записи заново по текущим настройкам и текущему состоянию записи. */
    public void replan(UUID bookingId) {
        var current = settings.readForShare();
        locks.booking(bookingId);
        var booking = bookings.find(bookingId, BOOKINGS_READER);
        if (booking.isEmpty()) {
            queue.deletePendingReminders(bookingId);
        } else if (!booking.get().status().isActive()) {
            queue.cancelPendingReminders(bookingId);
        } else {
            replanBooking(booking.get(), current);
        }
    }

    /** Визита не будет (отмена, выполнена, не пришла): неотправленные напоминания записи отменяются. */
    public void cancel(UUID bookingId) {
        settings.readForShare();
        locks.booking(bookingId);
        queue.cancelPendingReminders(bookingId);
    }

    /**
     * Рассчитывает заново напоминания всех предстоящих записей по новым настройкам. Вызывающий уже держит строку
     * настроек на запись ({@code FOR UPDATE}) с этими настройками: все прочие изменения напоминаний ждут фиксации, а
     * прошедшие до смены уже зафиксированы — список записей и их состояние актуальны, блокировки записей не нужны.
     */
    public void replanUpcoming(ReminderSettings updated) {
        for (var booking : bookings.upcoming(BOOKINGS_READER)) {
            replanBooking(booking, updated);
        }
    }

    private void replanBooking(BookingView booking, ReminderSettings current) {
        queue.deletePendingReminders(booking.id());
        if (booking.clientErasedAt() != null) {
            return;
        }
        var recipient = new Recipient.Client(booking.clientId());
        for (var reminder : schedule.plan(booking.agreedStart(), booking.slotStartsAt(), current, clock.instant())) {
            var dedupKey = "reminder:" + reminder.kind().name() + ":" + booking.id() + ":" + reminder.dueAt();
            queue.enqueue(new NewMessage(
                    reminder.kind(), recipient, booking.id(), reminder.dueAt(), reminder.expiresAt(), dedupKey));
        }
    }
}
