package gag.nails.booking.internal.booking;

import gag.nails.booking.BookingService;
import gag.nails.booking.command.CreateBooking;
import gag.nails.booking.error.ConsentMissingException;
import gag.nails.booking.error.InvalidTransitionException;
import gag.nails.booking.error.LateRescheduleNotAllowedException;
import gag.nails.booking.error.LimitReachedException;
import gag.nails.booking.error.TooLateToBookException;
import gag.nails.booking.error.VisitNotStartedException;
import gag.nails.booking.model.BookingCreator;
import gag.nails.booking.model.BookingStatus;
import gag.nails.booking.model.BookingView;
import gag.nails.booking.model.CancelReason;
import gag.nails.catalog.CatalogQueries;
import gag.nails.clients.ClientQueries;
import gag.nails.clients.error.ClientBlockedException;
import gag.nails.clients.error.ClientErasedException;
import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.clients.model.ClientView;
import gag.nails.clients.model.ConsentKind;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.common.validation.CommandValidator;
import gag.nails.schedule.SlotOccupation;
import gag.nails.schedule.error.SlotOccupiedException;
import gag.nails.schedule.model.SlotView;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultBookingService implements BookingService {

    private static final Set<ConsentKind> REQUIRED_CONSENTS = ConsentKind.requiredKinds();
    private static final int MAX_COMMENT_LENGTH = 500;
    private static final int MAX_MASTER_NOTE_LENGTH = 2000;

    private final BookingRepository bookings;
    private final BookingFinder finder;
    private final BookingWriter writer;
    private final ClientLocks locks;
    private final CurrentRules currentRules;
    private final BookingEvents events;
    private final BookingCancellation cancellation;
    private final CatalogQueries catalog;
    private final ClientQueries clientQueries;
    private final SlotOccupation occupation;
    private final CommandValidator validator;

    DefaultBookingService(
            BookingRepository bookings,
            BookingFinder finder,
            BookingWriter writer,
            ClientLocks locks,
            CurrentRules currentRules,
            BookingEvents events,
            BookingCancellation cancellation,
            CatalogQueries catalog,
            ClientQueries clientQueries,
            SlotOccupation occupation,
            CommandValidator validator) {
        this.bookings = bookings;
        this.finder = finder;
        this.writer = writer;
        this.locks = locks;
        this.currentRules = currentRules;
        this.events = events;
        this.cancellation = cancellation;
        this.catalog = catalog;
        this.clientQueries = clientQueries;
        this.occupation = occupation;
        this.validator = validator;
    }

    @Override
    public BookingView createByClient(CreateBooking command, Actor actor) {
        var clientId = actor.requireClientId();
        // Доступ раньше валидации; команда без клиента — ошибка валидации, а не отказ в доступе.
        var requestedClientId = command.clientId();
        if (requestedClientId != null && !clientId.equals(requestedClientId)) {
            throw new AccessDeniedException();
        }
        validator.requireValid(command);
        var rules = currentRules.get();
        // Блокировка клиента до подсчёта: параллельная запись того же клиента ждёт конца этой транзакции.
        // Карточка читается под ней же: параллельная блокировка или обезличивание либо уже видны, либо подождут.
        locks.acquire(clientId);
        var client = requireClientMayBookSelf(clientId);
        var limit = rules.settings().maxActiveBookings();
        if (bookings.countByClientIdAndStatusIn(clientId, BookingStatus.active()) >= limit) {
            throw new LimitReachedException(limit);
        }
        if (!clientQueries.hasCurrentConsents(clientId, REQUIRED_CONSENTS)) {
            throw new ConsentMissingException(clientId);
        }
        var slot = occupation.get(command.slotId());
        if (rules.tooLateToBook(slot.startsAt())) {
            throw new TooLateToBookException(slot.startsAt(), rules.settings().bookingCutoffMinutes());
        }
        return create(command, slot, BookingCreator.CLIENT, client.prepaymentRequired(), rules, actor);
    }

    @Override
    public BookingView createByMaster(CreateBooking command, boolean awaitPrepayment, Actor actor) {
        actor.requireWrite(Section.BOOKINGS);
        validator.requireValid(command);
        var rules = currentRules.get();
        var slot = occupation.get(command.slotId());
        requireSlotAllowedForMaster(slot, awaitPrepayment, rules, "slotId", "awaitPrepayment");
        // Та же блокировка, что при самозаписи: параллельная запись клиента увидит эту при подсчёте лимита.
        // Клиент проверяется под ней: параллельное объединение карточек либо уже закончилось, либо подождёт.
        locks.acquire(command.clientId());
        // Заблокированного мастер записать может, обезличенного — нет: записывать больше некого.
        requireNotErased(requireClient(command.clientId()));
        return create(command, slot, BookingCreator.MASTER, awaitPrepayment, rules, actor);
    }

    @Override
    public BookingView cancel(UUID bookingId, Actor actor) {
        var booking = finder.requireAccessibleForUpdate(bookingId, actor);
        booking.requireActive("cancel");
        var rules = currentRules.get();
        if (!actor.canWrite(Section.BOOKINGS) && rules.visitStarted(booking.slotStartsAt())) {
            throw new InvalidTransitionException(bookingId, booking.status(), "cancel a started visit");
        }
        cancellation.cancel(booking, cancelReason(actor, rules, booking), rules.now());
        return booking.toView(actor);
    }

    @Override
    public BookingView refuseLateArrival(UUID bookingId, Actor actor) {
        actor.requireWrite(Section.BOOKINGS);
        var booking = finder.requireForUpdate(bookingId);
        booking.requireActive("refuse late arrival");
        var rules = currentRules.get();
        if (!rules.visitStarted(booking.slotStartsAt())) {
            throw new VisitNotStartedException(bookingId, booking.slotStartsAt());
        }
        cancellation.cancel(booking, CancelReason.LATE_ARRIVAL, rules.now());
        return booking.toView(actor);
    }

    @Override
    public BookingView reschedule(UUID bookingId, UUID newSlotId, Actor actor) {
        var booking = finder.requireAccessibleForUpdate(bookingId, actor);
        booking.requireActive("reschedule");
        var rules = currentRules.get();
        var byClient = !actor.canWrite(Section.BOOKINGS);
        if (byClient) {
            // Порядок блокировок: запись уже взята, теперь клиент — как при создании записи.
            locks.acquire(booking.clientId());
            requireClientMayBookSelf(booking.clientId());
        }
        if (byClient && rules.visitStarted(booking.slotStartsAt())) {
            throw new InvalidTransitionException(bookingId, booking.status(), "reschedule a started visit");
        }
        // Иначе перенос продлил бы уже истёкший срок предоплаты до начала нового слота.
        if (byClient && booking.isPrepaymentOverdue(rules.now())) {
            throw new InvalidTransitionException(bookingId, booking.status(), "reschedule with overdue prepayment");
        }
        if (byClient && !rules.freeCancellationOpen(booking.slotStartsAt())) {
            throw new LateRescheduleNotAllowedException(
                    bookingId, rules.settings().freeCancelWindowHours());
        }
        if (newSlotId.equals(booking.slotId())) {
            throw new ValidationException("newSlotId", "must differ from the current slot");
        }
        var newSlot = occupation.get(newSlotId);
        if (byClient && rules.tooLateToBook(newSlot.startsAt())) {
            throw new TooLateToBookException(
                    newSlot.startsAt(), rules.settings().bookingCutoffMinutes());
        }
        if (!byClient) {
            var awaitsPrepayment = booking.status() == BookingStatus.AWAITING_PREPAYMENT;
            requireSlotAllowedForMaster(newSlot, awaitsPrepayment, rules, "newSlotId", "newSlotId");
        }
        if (newSlot.occupied()) {
            throw new SlotOccupiedException(newSlotId);
        }
        var oldSlotId = booking.slotId();
        var oldSlotStartsAt = booking.slotStartsAt();
        var currentDeadline = booking.prepaymentDeadline();
        var deadline = currentDeadline == null
                ? null
                : rules.prepaymentDeadlineAfterReschedule(currentDeadline, newSlot.startsAt());
        // Сначала освободить старый слот, потом занять новый: запись держит не больше одного слота.
        occupation.release(oldSlotId, booking.id());
        booking.moveTo(newSlot, deadline);
        writer.flushMoved(booking);
        occupation.occupy(newSlotId, booking.id());
        events.rescheduled(booking, oldSlotId, oldSlotStartsAt, rules.now());
        return booking.toView(actor);
    }

    @Override
    public BookingView updateComment(UUID bookingId, @Nullable String comment, Actor actor) {
        var booking = finder.requireAccessibleForUpdate(bookingId, actor);
        // Длина в символах (code points), как char_length в CHECK базы, а не в единицах UTF-16.
        if (comment != null && comment.codePointCount(0, comment.length()) > MAX_COMMENT_LENGTH) {
            throw new ValidationException("comment", "must be at most " + MAX_COMMENT_LENGTH + " characters");
        }
        booking.requireActive("update comment");
        booking.setComment(comment);
        return booking.toView(actor);
    }

    @Override
    public void updateMasterNote(UUID bookingId, String note, Actor actor) {
        actor.requireWrite(Section.BOOKINGS);
        var booking = finder.requireForUpdate(bookingId);
        // Длина в символах (code points), как char_length в CHECK базы, а не в единицах UTF-16.
        if (note.codePointCount(0, note.length()) > MAX_MASTER_NOTE_LENGTH) {
            throw new ValidationException("note", "must be at most " + MAX_MASTER_NOTE_LENGTH + " characters");
        }
        booking.setMasterNote(note);
    }

    private BookingView create(
            CreateBooking command,
            SlotView slot,
            BookingCreator creator,
            boolean awaitPrepayment,
            BookingRules rules,
            Actor actor) {
        if (slot.occupied()) {
            throw new SlotOccupiedException(slot.id());
        }
        var services = catalog.requireActive(ServiceSelection.ids(command.mainServiceId(), command.addonServiceIds()));
        ServiceSelection.requireKinds(services);
        var deadline = awaitPrepayment ? rules.prepaymentDeadline(slot.startsAt()) : null;
        var booking = writer.insert(
                BookingEntity.create(command.clientId(), slot, creator, deadline, command.comment(), services));
        // Запись уже в базе (flush в writer), теперь слот: при гонке проходит один, второй откатывается целиком.
        occupation.occupy(slot.id(), booking.id());
        events.created(booking, rules.now());
        return booking.toView(actor);
    }

    private ClientView requireClient(UUID clientId) {
        return clientQueries.byId(clientId).orElseThrow(() -> new ClientNotFoundException(clientId));
    }

    /** Сам записывается и переносит только клиент, которого не заблокировали и не обезличили. */
    private ClientView requireClientMayBookSelf(UUID clientId) {
        var client = requireNotErased(requireClient(clientId));
        if (client.blocked()) {
            throw new ClientBlockedException(clientId);
        }
        return client;
    }

    private static ClientView requireNotErased(ClientView client) {
        if (client.erased()) {
            throw new ClientErasedException(client.id());
        }
        return client;
    }

    /**
     * Мастеру доступен и слот в прошлом, но не старше срока исправления отметок; запись, ждущая
     * предоплату, на уже начавшийся слот не ставится.
     */
    private static void requireSlotAllowedForMaster(
            SlotView slot, boolean awaitsPrepayment, BookingRules rules, String slotField, String prepaymentField) {
        if (rules.olderThanCorrectionWindow(slot.startsAt())) {
            throw new ValidationException(slotField, "slot is older than the correction window");
        }
        if (awaitsPrepayment && rules.visitStarted(slot.startsAt())) {
            throw new ValidationException(prepaymentField, "not allowed for a slot in the past");
        }
    }

    /** Мастер отменяет без последствий; клиент — бесплатно в окне, позже это поздняя отмена. */
    private static CancelReason cancelReason(Actor actor, BookingRules rules, BookingEntity booking) {
        if (actor.canWrite(Section.BOOKINGS)) {
            return CancelReason.MASTER;
        }
        return rules.freeCancellationOpen(booking.slotStartsAt()) ? CancelReason.CLIENT : CancelReason.CLIENT_LATE;
    }
}
