package gag.nails.booking.internal.marks;

import gag.nails.booking.VisitMarks;
import gag.nails.booking.command.UpdateActualServices;
import gag.nails.booking.command.UpdateVisit;
import gag.nails.booking.error.CorrectionWindowClosedException;
import gag.nails.booking.error.VisitNotStartedException;
import gag.nails.booking.internal.booking.BookingEntity;
import gag.nails.booking.internal.booking.BookingEvents;
import gag.nails.booking.internal.booking.BookingFinder;
import gag.nails.booking.internal.booking.BookingRules;
import gag.nails.booking.internal.booking.CurrentRules;
import gag.nails.booking.internal.booking.ServiceSelection;
import gag.nails.booking.model.BookingStatus;
import gag.nails.booking.model.BookingView;
import gag.nails.booking.model.MarkResult;
import gag.nails.catalog.CatalogQueries;
import gag.nails.catalog.model.ServiceView;
import gag.nails.clients.ClientQueries;
import gag.nails.clients.ClientService;
import gag.nails.clients.command.PrepaymentFlagChange;
import gag.nails.clients.model.ClientView;
import gag.nails.clients.model.PrepaymentFlagReason;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.ErrorCodes;
import gag.nails.common.error.InternalException;
import gag.nails.common.error.UserFacingException;
import gag.nails.common.error.ValidationException;
import gag.nails.common.validation.CommandValidator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Отметки визита. Каждая отдельная операция — одна транзакция; пакетная отметка транзакцией не оборачивается
 * и открывает свою на каждую запись ({@link #completeAsPlanned}).
 */
@Service
class DefaultVisitMarks implements VisitMarks {

    /** Как {@code CHECK (char_length(amount_note) BETWEEN 1 AND 200)} в базе. */
    private static final int MAX_AMOUNT_NOTE_LENGTH = 200;

    private static final Logger log = LoggerFactory.getLogger(DefaultVisitMarks.class);

    private final BookingFinder finder;
    private final CurrentRules currentRules;
    private final BookingEvents events;
    private final ClientService clients;
    private final ClientQueries clientQueries;
    private final CatalogQueries catalog;
    private final CommandValidator validator;
    private final TransactionTemplate transaction;

    DefaultVisitMarks(
            BookingFinder finder,
            CurrentRules currentRules,
            BookingEvents events,
            ClientService clients,
            ClientQueries clientQueries,
            CatalogQueries catalog,
            CommandValidator validator,
            TransactionTemplate transaction) {
        this.finder = finder;
        this.currentRules = currentRules;
        this.events = events;
        this.clients = clients;
        this.clientQueries = clientQueries;
        this.catalog = catalog;
        this.validator = validator;
        this.transaction = transaction;
    }

    @Override
    @Transactional
    public BookingView complete(UUID bookingId, Actor actor) {
        actor.requireWrite(Section.BOOKINGS);
        return completeInCurrentTransaction(bookingId).toView(actor);
    }

    /**
     * Каждая запись — своя транзакция из {@link TransactionTemplate}: исключение одной откатывает только её. Внутри
     * чужой транзакции так не выйдет (исключение пометило бы к откату общую), поэтому вызов в ней — ошибка кода.
     */
    @Override
    public List<MarkResult> completeAsPlanned(List<UUID> bookingIds, Actor actor) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new InternalException("bookings must be completed as planned outside a transaction");
        }
        actor.requireWrite(Section.BOOKINGS);
        // Предел — на присланный список, до удаления повторов: размер запроса ограничен сам по себе.
        if (bookingIds.size() > MAX_BATCH) {
            throw new ValidationException("bookingIds", "must contain at most " + MAX_BATCH + " ids");
        }
        if (bookingIds.stream().anyMatch(Objects::isNull)) {
            throw new ValidationException("bookingIds", "must not contain empty ids");
        }
        var distinct = new LinkedHashSet<>(bookingIds);
        return distinct.stream().map(this::completeInOwnTransaction).toList();
    }

    @Override
    @Transactional
    public BookingView markNoShow(UUID bookingId, Actor actor) {
        actor.requireWrite(Section.BOOKINGS);
        var booking = finder.requireForUpdate(bookingId);
        var rules = currentRules.get();
        requireMarkAllowed(booking, rules);
        booking.markNoShow(rules.now());
        // Признак уже стоит по другой причине: журнал признака не трогаем.
        if (!owesPrepayment(booking.clientId())) {
            setPrepaymentFlag(booking, true, PrepaymentFlagReason.NO_SHOW);
        }
        events.noShow(booking, rules.now());
        return booking.toView(actor);
    }

    @Override
    @Transactional
    public BookingView updateActualServices(UUID bookingId, UpdateActualServices command, Actor actor) {
        actor.requireWrite(Section.BOOKINGS);
        validator.requireValid(command);
        var booking = requireCorrectableVisit(bookingId);
        booking.replaceActualServices(
                actualServices(command.mainServiceId(), command.addonServiceIds()), command.finalAmount());
        return booking.toView(actor);
    }

    @Override
    @Transactional
    public BookingView updateVisit(UUID bookingId, UpdateVisit command, Actor actor) {
        actor.requireWrite(Section.BOOKINGS);
        validator.requireValid(command);
        var note = requireValidNote(command.amountNote());
        var booking = requireCorrectableVisit(bookingId);
        booking.replaceVisit(
                actualServices(command.mainServiceId(), command.addonServiceIds()), command.finalAmount(), note);
        return booking.toView(actor);
    }

    /**
     * Итог одной записи пакета. Отказ инфраструктуры (транзакцию не начать, база недоступна) прерывает весь пакет:
     * остальные записи упали бы так же, и вместо пятидесяти одинаковых кодов вызывающий получает одну ошибку.
     */
    private MarkResult completeInOwnTransaction(UUID bookingId) {
        try {
            transaction.executeWithoutResult(status -> completeInCurrentTransaction(bookingId));
            return new MarkResult(bookingId, null);
        } catch (UserFacingException e) {
            // Ожидаемый отказ записи (не начата, уже отмечена…): код уходит в итог, лог — только для отладки.
            log.debug("booking {} not completed as planned: {}", bookingId, e.code());
            return new MarkResult(bookingId, e.code());
        } catch (CannotCreateTransactionException | DataAccessResourceFailureException e) {
            throw e;
        } catch (PessimisticLockingFailureException e) {
            log.warn(
                    "booking {} not completed as planned: lock failure {}",
                    bookingId,
                    e.getClass().getSimpleName());
            return new MarkResult(bookingId, ErrorCodes.BUSY);
        } catch (RuntimeException e) {
            log.error("completing booking {} as planned failed", bookingId, e);
            return new MarkResult(bookingId, ErrorCodes.INTERNAL);
        }
    }

    /** Отметка «выполнен» в транзакции вызывающего, под блокировкой строки; уровень доступа он уже проверил. */
    private BookingEntity completeInCurrentTransaction(UUID bookingId) {
        var booking = finder.requireForUpdate(bookingId);
        var rules = currentRules.get();
        requireMarkAllowed(booking, rules);
        var correctsNoShow = booking.status() == BookingStatus.NO_SHOW;
        booking.complete(rules.now());
        if (correctsNoShow) {
            clearFlagSetByNoShowOf(booking);
        } else if (booking.prepaymentReceivedAt() != null && owesPrepayment(booking.clientId())) {
            setPrepaymentFlag(booking, false, PrepaymentFlagReason.VISIT_WITH_PREPAYMENT_COMPLETED);
        }
        events.completed(booking, rules.now());
        return booking;
    }

    /**
     * Запись под блокировкой строки для правки визита в пределах срока исправления. Состояние проверит сущность
     * (нужен выполненный визит); здесь — только срок.
     */
    private BookingEntity requireCorrectableVisit(UUID bookingId) {
        var booking = finder.requireForUpdate(bookingId);
        var rules = currentRules.get();
        if (booking.status() == BookingStatus.COMPLETED && !rules.correctionWindowOpen(booking.slotStartsAt())) {
            throw new CorrectionWindowClosedException(
                    bookingId, rules.settings().markCorrectionWindowDays());
        }
        return booking;
    }

    /** Не requireActive каталога: услугу могли скрыть уже после визита, а оказана она была. */
    private List<ServiceView> actualServices(UUID mainServiceId, List<UUID> addonServiceIds) {
        var services = ServiceSelection.ids(mainServiceId, addonServiceIds).stream()
                .map(catalog::get)
                .toList();
        ServiceSelection.requireKinds(services);
        return services;
    }

    /**
     * Причина суммы без пробелов по краям; пустая — причины нет. Длина в символах (code points), как {@code
     * char_length} в CHECK базы, а не в единицах UTF-16.
     */
    private static @Nullable String requireValidNote(@Nullable String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        var stripped = note.strip();
        if (stripped.codePointCount(0, stripped.length()) > MAX_AMOUNT_NOTE_LENGTH) {
            throw new ValidationException("amountNote", "must be at most " + MAX_AMOUNT_NOTE_LENGTH + " characters");
        }
        return stripped;
    }

    /**
     * Активной записи отметка ставится после начала слота; поставленная отметка меняется в пределах
     * срока исправления. Отменённую запись отвергнет сама сущность.
     */
    private static void requireMarkAllowed(BookingEntity booking, BookingRules rules) {
        switch (booking.status()) {
            case AWAITING_PREPAYMENT, CONFIRMED -> {
                if (!rules.visitStarted(booking.slotStartsAt())) {
                    throw new VisitNotStartedException(booking.id(), booking.slotStartsAt());
                }
            }
            case COMPLETED, NO_SHOW -> {
                if (!rules.correctionWindowOpen(booking.slotStartsAt())) {
                    throw new CorrectionWindowClosedException(
                            booking.id(), rules.settings().markCorrectionWindowDays());
                }
            }
            case CANCELLED -> {}
        }
    }

    /**
     * Исправление «не пришла» снимает признак, только если он стоит именно за эту неявку: последняя
     * строка журнала признака — NO_SHOW по этой записи. Поставленный по другой причине остаётся.
     */
    private void clearFlagSetByNoShowOf(BookingEntity booking) {
        if (!owesPrepayment(booking.clientId())) {
            return;
        }
        var setByThisNoShow = clientQueries
                .lastPrepaymentChange(booking.clientId())
                .filter(last -> last.required()
                        && last.reason() == PrepaymentFlagReason.NO_SHOW
                        && booking.id().equals(last.bookingId()))
                .isPresent();
        if (setByThisNoShow) {
            setPrepaymentFlag(booking, false, PrepaymentFlagReason.NO_SHOW_CORRECTED);
        }
    }

    private boolean owesPrepayment(UUID clientId) {
        return clientQueries.byId(clientId).map(ClientView::prepaymentRequired).orElse(false);
    }

    /** Вызывается, только когда значение действительно меняется: пустых строк в журнале клиента нет. */
    private void setPrepaymentFlag(BookingEntity booking, boolean required, PrepaymentFlagReason reason) {
        clients.applySystemPrepaymentChange(
                booking.clientId(), new PrepaymentFlagChange(required, reason, booking.id(), null));
    }
}
