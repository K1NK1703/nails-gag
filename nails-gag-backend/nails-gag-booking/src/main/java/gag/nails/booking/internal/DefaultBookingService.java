package gag.nails.booking.internal;

import gag.nails.booking.BookingCreator;
import gag.nails.booking.BookingService;
import gag.nails.booking.BookingStatus;
import gag.nails.booking.BookingView;
import gag.nails.booking.CancelReason;
import gag.nails.booking.CreateBooking;
import gag.nails.booking.error.ConsentMissingException;
import gag.nails.booking.error.LimitReachedException;
import gag.nails.booking.error.TooLateToBookException;
import gag.nails.booking.error.VisitNotStartedException;
import gag.nails.catalog.CatalogQueries;
import gag.nails.clients.ClientQueries;
import gag.nails.clients.ClientView;
import gag.nails.clients.ConsentKind;
import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.common.validation.CommandValidator;
import gag.nails.schedule.SlotOccupation;
import gag.nails.schedule.SlotView;
import gag.nails.schedule.error.SlotOccupiedException;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultBookingService implements BookingService {

    private static final Set<ConsentKind> REQUIRED_CONSENTS = Set.of(ConsentKind.PERSONAL_DATA, ConsentKind.OFFER);
    private static final int MAX_COMMENT_LENGTH = 500;

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
        locks.acquire(clientId);
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
        var awaitPrepayment = requireClient(clientId).prepaymentRequired();
        return create(command, slot, BookingCreator.CLIENT, awaitPrepayment, rules);
    }

    @Override
    public BookingView createByMaster(CreateBooking command, boolean awaitPrepayment, Actor actor) {
        actor.requireMaster();
        validator.requireValid(command);
        var rules = currentRules.get();
        var slot = occupation.get(command.slotId());
        if (rules.olderThanCorrectionWindow(slot.startsAt())) {
            throw new ValidationException("slotId", "slot is older than the correction window");
        }
        if (awaitPrepayment && rules.visitStarted(slot.startsAt())) {
            throw new ValidationException("awaitPrepayment", "not allowed for a slot in the past");
        }
        requireClient(command.clientId());
        // Та же блокировка, что при самозаписи: параллельная запись клиента увидит эту при подсчёте лимита.
        locks.acquire(command.clientId());
        return create(command, slot, BookingCreator.MASTER, awaitPrepayment, rules);
    }

    @Override
    public BookingView cancel(UUID bookingId, Actor actor) {
        var booking = finder.requireAccessible(bookingId, actor);
        var rules = currentRules.get();
        return cancellation.cancel(booking, cancelReason(actor, rules, booking), rules.now());
    }

    @Override
    public BookingView refuseLateArrival(UUID bookingId, Actor actor) {
        actor.requireMaster();
        var booking = finder.require(bookingId);
        booking.requireActive("refuse late arrival");
        var rules = currentRules.get();
        if (!rules.visitStarted(booking.slotStartsAt())) {
            throw new VisitNotStartedException(bookingId, booking.slotStartsAt());
        }
        return cancellation.cancel(booking, CancelReason.LATE_ARRIVAL, rules.now());
    }

    @Override
    public BookingView updateComment(UUID bookingId, @Nullable String comment, Actor actor) {
        var booking = finder.requireAccessible(bookingId, actor);
        if (comment != null && comment.length() > MAX_COMMENT_LENGTH) {
            throw new ValidationException("comment", "must be at most " + MAX_COMMENT_LENGTH + " characters");
        }
        booking.requireActive("update comment");
        booking.setComment(comment);
        return booking.toView();
    }

    @Override
    public void updateMasterNote(UUID bookingId, String note, Actor actor) {
        actor.requireMaster();
        finder.require(bookingId).setMasterNote(note);
    }

    private BookingView create(
            CreateBooking command, SlotView slot, BookingCreator creator, boolean awaitPrepayment, BookingRules rules) {
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
        return booking.toView();
    }

    private ClientView requireClient(UUID clientId) {
        return clientQueries.byId(clientId).orElseThrow(() -> new ClientNotFoundException(clientId));
    }

    /** Мастер отменяет без последствий; клиент — бесплатно в окне, позже это поздняя отмена. */
    private static CancelReason cancelReason(Actor actor, BookingRules rules, BookingEntity booking) {
        if (actor.isMaster()) {
            return CancelReason.MASTER;
        }
        return rules.freeCancellationOpen(booking.slotStartsAt()) ? CancelReason.CLIENT : CancelReason.CLIENT_LATE;
    }
}
