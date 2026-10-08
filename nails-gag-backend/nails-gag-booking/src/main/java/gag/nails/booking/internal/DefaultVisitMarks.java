package gag.nails.booking.internal;

import gag.nails.booking.BookingStatus;
import gag.nails.booking.BookingView;
import gag.nails.booking.UpdateActualServices;
import gag.nails.booking.VisitMarks;
import gag.nails.booking.error.CorrectionWindowClosedException;
import gag.nails.booking.error.VisitNotStartedException;
import gag.nails.catalog.CatalogQueries;
import gag.nails.clients.ClientQueries;
import gag.nails.clients.ClientService;
import gag.nails.clients.command.PrepaymentFlagChange;
import gag.nails.clients.model.ClientView;
import gag.nails.clients.model.PrepaymentFlagReason;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.validation.CommandValidator;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultVisitMarks implements VisitMarks {

    private final BookingFinder finder;
    private final CurrentRules currentRules;
    private final BookingEvents events;
    private final ClientService clients;
    private final ClientQueries clientQueries;
    private final CatalogQueries catalog;
    private final CommandValidator validator;

    DefaultVisitMarks(
            BookingFinder finder,
            CurrentRules currentRules,
            BookingEvents events,
            ClientService clients,
            ClientQueries clientQueries,
            CatalogQueries catalog,
            CommandValidator validator) {
        this.finder = finder;
        this.currentRules = currentRules;
        this.events = events;
        this.clients = clients;
        this.clientQueries = clientQueries;
        this.catalog = catalog;
        this.validator = validator;
    }

    @Override
    public BookingView complete(UUID bookingId, Actor actor) {
        actor.requireWrite(Section.BOOKINGS);
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
        return booking.toView();
    }

    @Override
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
        return booking.toView();
    }

    @Override
    public BookingView updateActualServices(UUID bookingId, UpdateActualServices command, Actor actor) {
        actor.requireWrite(Section.BOOKINGS);
        validator.requireValid(command);
        var booking = finder.requireForUpdate(bookingId);
        var rules = currentRules.get();
        // Состояние проверит сущность (нужен выполненный визит); здесь — только срок исправления.
        if (booking.status() == BookingStatus.COMPLETED && !rules.correctionWindowOpen(booking.slotStartsAt())) {
            throw new CorrectionWindowClosedException(
                    bookingId, rules.settings().markCorrectionWindowDays());
        }
        // Не requireActive каталога: услугу могли скрыть уже после визита, а оказана она была.
        var services = ServiceSelection.ids(command.mainServiceId(), command.addonServiceIds()).stream()
                .map(catalog::get)
                .toList();
        ServiceSelection.requireKinds(services);
        booking.replaceActualServices(services, command.finalAmount());
        return booking.toView();
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
