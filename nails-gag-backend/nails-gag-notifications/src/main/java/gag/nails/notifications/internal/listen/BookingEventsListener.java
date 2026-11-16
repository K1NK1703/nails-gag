package gag.nails.notifications.internal.listen;

import gag.nails.booking.event.BookingCancelled;
import gag.nails.booking.event.BookingCompleted;
import gag.nails.booking.event.BookingConfirmed;
import gag.nails.booking.event.BookingCreated;
import gag.nails.booking.event.BookingNoShow;
import gag.nails.booking.event.BookingRescheduled;
import gag.nails.booking.event.EarlierTimeAccepted;
import gag.nails.booking.event.EarlierTimeDeclined;
import gag.nails.booking.event.EarlierTimeProposed;
import gag.nails.booking.event.PrepaymentRequested;
import gag.nails.booking.model.BookingStatus;
import gag.nails.common.event.DomainEvent;
import gag.nails.identity.StaffDirectory;
import gag.nails.notifications.internal.preference.ChannelDefaults;
import gag.nails.notifications.internal.queue.MessageQueue;
import gag.nails.notifications.internal.queue.NewMessage;
import gag.nails.notifications.internal.queue.QuietHours;
import gag.nails.notifications.internal.queue.Recipient;
import gag.nails.notifications.internal.reminder.ReminderPlanner;
import gag.nails.notifications.model.MessageKind;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Сообщения о событиях записи — матрица спеки §5.1: на каждое событие один {@code switch}, который решает, какой вид
 * уходит клиентке и какой — сотрудникам. Слушатели — {@code @ApplicationModuleListener}: после фиксации события,
 * асинхронно, в своей транзакции, через реестр публикаций (событие, не обработанное до падения, повторяется после
 * рестарта). Повтор безопасен: ключ повтора сообщения — {@code <событие>:<запись>:<occurredAt>:<получатель>}.
 *
 * <p>Клиентке пишем на карточку из события; мастерское сообщение — каждому из
 * {@code StaffDirectory.recipients(BOOKINGS, READ)} на момент обработки, независимо от его каналов: каналы выбираются
 * при отправке. Срок отправки — {@code occurredAt} события, для клиентки — с переносом из тихих часов на их конец,
 * мастерские сообщения тихих часов не знают. Сообщение живёт сутки от срока отправки.
 *
 * <p>Напоминания о визите — в той же транзакции, до сообщений события ({@link ReminderPlanner}: его порядок
 * блокировок начинается со строки настроек): создание, подтверждение, перенос и принятое «пораньше» перепланируют
 * напоминания записи, отмена, выполнение и неявка — отменяют неотправленные. Сбой откатывает и сообщения события;
 * повтор безопасен. {@code BookingCompleted} и {@code BookingNoShow} сообщений не дают — только отмену напоминаний.
 */
@Component
class BookingEventsListener {

    /** Сколько живёт событийное сообщение после срока отправки. */
    private static final Duration EXPIRY = Duration.ofHours(24);

    private final MessageQueue queue;
    private final StaffDirectory staffDirectory;
    private final QuietHours quietHours;
    private final ReminderPlanner reminders;

    BookingEventsListener(
            MessageQueue queue, StaffDirectory staffDirectory, QuietHours quietHours, ReminderPlanner reminders) {
        this.queue = queue;
        this.staffDirectory = staffDirectory;
        this.quietHours = quietHours;
        this.reminders = reminders;
    }

    /**
     * Запись, созданная клиенткой, — мастеру всегда, клиентке — подтверждение, если запись подтверждена сразу.
     * Запись, созданная мастером, — клиентке, если подтверждена сразу. Запись, ждущую предоплату, клиентке объясняет
     * {@link PrepaymentRequested}, опубликованный вместе с этим событием.
     */
    @ApplicationModuleListener
    void on(BookingCreated event) {
        reminders.replan(event.bookingId());
        var confirmed = event.status() == BookingStatus.CONFIRMED;
        var plan = switch (event.createdBy()) {
            case CLIENT ->
                confirmed
                        ? Plan.both(MessageKind.BOOKING_RECEIVED, MessageKind.STAFF_NEW_BOOKING)
                        : Plan.staffOnly(MessageKind.STAFF_NEW_BOOKING);
            case MASTER -> confirmed ? Plan.clientOnly(MessageKind.BOOKED_BY_MASTER) : Plan.NONE;
        };
        enqueue(event, event.bookingId(), event.clientId(), plan);
    }

    @ApplicationModuleListener
    void on(PrepaymentRequested event) {
        enqueue(event, event.bookingId(), event.clientId(), Plan.clientOnly(MessageKind.PREPAYMENT_REQUESTED));
    }

    @ApplicationModuleListener
    void on(BookingConfirmed event) {
        reminders.replan(event.bookingId());
        enqueue(event, event.bookingId(), event.clientId(), Plan.clientOnly(MessageKind.CONFIRMED));
    }

    /** Новая причина отмены не скомпилируется, пока для неё не решено, кому писать. */
    @ApplicationModuleListener
    void on(BookingCancelled event) {
        reminders.cancel(event.bookingId());
        var plan = switch (event.reason()) {
            case CLIENT, CLIENT_LATE -> Plan.both(MessageKind.CANCELLED_BY_CLIENT, MessageKind.STAFF_CLIENT_CANCELLED);
            case MASTER, LATE_ARRIVAL, ACCOUNT_DELETED -> Plan.clientOnly(MessageKind.CANCELLED);
            case PREPAYMENT_TIMEOUT -> Plan.both(MessageKind.CANCELLED, MessageKind.STAFF_PREPAYMENT_TIMEOUT);
        };
        enqueue(event, event.bookingId(), event.clientId(), plan);
    }

    /** Инициатор {@code null} (событие прежней формы) — перенос мастером. */
    @ApplicationModuleListener
    void on(BookingRescheduled event) {
        reminders.replan(event.bookingId());
        var plan = switch (event.initiator()) {
            case CLIENT -> Plan.both(MessageKind.RESCHEDULED, MessageKind.STAFF_CLIENT_RESCHEDULED);
            case MASTER -> Plan.clientOnly(MessageKind.RESCHEDULED);
            case null -> Plan.clientOnly(MessageKind.RESCHEDULED);
        };
        enqueue(event, event.bookingId(), event.clientId(), plan);
    }

    @ApplicationModuleListener
    void on(EarlierTimeProposed event) {
        enqueue(event, event.bookingId(), event.clientId(), Plan.clientOnly(MessageKind.EARLIER_PROPOSED));
    }

    @ApplicationModuleListener
    void on(EarlierTimeAccepted event) {
        reminders.replan(event.bookingId());
        enqueue(event, event.bookingId(), event.clientId(), Plan.staffOnly(MessageKind.STAFF_EARLIER_ACCEPTED));
    }

    @ApplicationModuleListener
    void on(EarlierTimeDeclined event) {
        enqueue(event, event.bookingId(), event.clientId(), Plan.staffOnly(MessageKind.STAFF_EARLIER_DECLINED));
    }

    /** Визит состоялся — напоминать не о чем; сообщений нет. */
    @ApplicationModuleListener
    void on(BookingCompleted event) {
        reminders.cancel(event.bookingId());
    }

    /** Клиентка не пришла — напоминать не о чем; сообщений нет. */
    @ApplicationModuleListener
    void on(BookingNoShow event) {
        reminders.cancel(event.bookingId());
    }

    private void enqueue(DomainEvent event, UUID bookingId, UUID clientId, Plan plan) {
        var occurredAt = event.occurredAt();
        var clientKind = plan.client();
        if (clientKind != null) {
            enqueue(event, bookingId, clientKind, new Recipient.Client(clientId), quietHours.shift(occurredAt));
        }
        var staffKind = plan.staff();
        if (staffKind != null) {
            for (var staff : staffDirectory.recipients(ChannelDefaults.STAFF_SECTION, ChannelDefaults.STAFF_LEVEL)) {
                enqueue(event, bookingId, staffKind, new Recipient.Account(staff.accountId()), occurredAt);
            }
        }
    }

    private void enqueue(DomainEvent event, UUID bookingId, MessageKind kind, Recipient recipient, Instant dueAt) {
        queue.enqueue(new NewMessage(
                kind,
                recipient,
                bookingId,
                dueAt,
                dueAt.plus(EXPIRY),
                dedupKey(event, bookingId, recipient),
                event.occurredAt()));
    }

    /**
     * {@code <событие>:<запись>:<occurredAt>:<получатель>}, например
     * {@code BookingCancelled:<uuid>:2026-10-01T06:00:00Z:client:<uuid>} — не длиннее 140 символов. Момент — из
     * события, не после переноса: повтор события даёт тот же ключ, даже если настройки тихих часов изменились.
     */
    private static String dedupKey(DomainEvent event, UUID bookingId, Recipient recipient) {
        return event.getClass().getSimpleName() + ":" + bookingId + ":" + event.occurredAt() + ":" + recipient.key();
    }

    /**
     * Кому пишем о событии: вид сообщения клиентке и вид мастерского сообщения; {@code null} — этому получателю
     * ничего.
     */
    private record Plan(
            @Nullable MessageKind client, @Nullable MessageKind staff) {

        static final Plan NONE = new Plan(null, null);

        static Plan clientOnly(MessageKind kind) {
            return new Plan(kind, null);
        }

        static Plan staffOnly(MessageKind kind) {
            return new Plan(null, kind);
        }

        static Plan both(MessageKind client, MessageKind staff) {
            return new Plan(client, staff);
        }
    }
}
