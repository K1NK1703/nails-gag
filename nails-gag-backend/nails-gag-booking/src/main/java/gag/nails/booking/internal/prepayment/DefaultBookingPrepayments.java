package gag.nails.booking.internal.prepayment;

import gag.nails.booking.BookingPrepayments;
import gag.nails.booking.error.CorrectionWindowClosedException;
import gag.nails.booking.internal.booking.BookingEntity;
import gag.nails.booking.internal.booking.BookingEvents;
import gag.nails.booking.internal.booking.BookingFinder;
import gag.nails.booking.internal.booking.BookingRules;
import gag.nails.booking.internal.booking.CurrentRules;
import gag.nails.booking.internal.booking.PrepaymentOverride;
import gag.nails.booking.model.BookingView;
import gag.nails.booking.model.PrepaymentOutcome;
import gag.nails.clients.ClientQueries;
import gag.nails.clients.ClientService;
import gag.nails.clients.model.ClientView;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.ValidationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultBookingPrepayments implements BookingPrepayments {

    /** Сумма — {@code numeric(12, 2)}: до десяти цифр в рублях и две в копейках. */
    private static final int AMOUNT_INTEGER_DIGITS = 10;

    private static final int AMOUNT_FRACTION_DIGITS = 2;

    private final BookingFinder finder;
    private final CurrentRules currentRules;
    private final BookingEvents events;
    private final ClientService clients;
    private final ClientQueries clientQueries;

    DefaultBookingPrepayments(
            BookingFinder finder,
            CurrentRules currentRules,
            BookingEvents events,
            ClientService clients,
            ClientQueries clientQueries) {
        this.finder = finder;
        this.currentRules = currentRules;
        this.events = events;
        this.clients = clients;
        this.clientQueries = clientQueries;
    }

    @Override
    public BookingView markReceived(UUID bookingId, Actor actor) {
        actor.requireWrite(Section.BOOKINGS);
        var booking = finder.requireForUpdate(bookingId);
        var rules = currentRules.get();
        // Состояние — до вычисления суммы: записи, не ждущей предоплату, сумма не нужна.
        booking.requireAwaitingPrepayment();
        // Процент — из действующих правил, прайс — выбранного при записи состава.
        return receive(booking, rules.defaultPrepaymentAmount(booking.plannedPrice()), rules, actor);
    }

    @Override
    public BookingView markReceived(UUID bookingId, BigDecimal amount, Actor actor) {
        actor.requireWrite(Section.BOOKINGS);
        var valid = requireValidAmount(amount);
        var booking = finder.requireForUpdate(bookingId);
        return receive(booking, valid, currentRules.get(), actor);
    }

    @Override
    public BookingView confirmWithoutPrepayment(UUID bookingId, Actor actor) {
        actor.requireWrite(Section.BOOKINGS);
        // Признак клиента снимается от имени сотрудника — нужен и уровень изменения «Клиентов». Оба
        // уровня проверяются до изменений, чтобы отказ не оставил запись подтверждённой.
        actor.requireWrite(Section.CLIENTS);
        var booking = finder.requireForUpdate(bookingId);
        var now = currentRules.get().now();
        booking.confirmWithoutPrepayment();
        // Сотрудник решил не требовать предоплату: признак снимается с причиной MASTER_WAIVED. Если его
        // и не было (предоплату запросили при записи), журнал признака не трогаем. Обезличенную карточку не меняет
        // никто: запись подтверждается, признак и журнал карточки остаются как есть.
        if (owesPrepaymentAndIsLive(booking.clientId())) {
            clients.setPrepaymentRequired(booking.clientId(), false, null, actor);
        }
        events.confirmed(booking, now);
        return booking.toView(actor);
    }

    @Override
    public BookingView setOutcome(UUID bookingId, PrepaymentOutcome outcome, Actor actor) {
        actor.requireWrite(Section.BOOKINGS);
        var override = switch (outcome) {
            case KEPT -> PrepaymentOverride.KEPT;
            case RETURNED -> PrepaymentOverride.RETURNED;
            case APPLIED -> throw new ValidationException("outcome", "must be KEPT or RETURNED");
        };
        // Состояние читается и переопределение пишется под блокировкой строки записи.
        var booking = finder.requireForUpdate(bookingId);
        var rules = currentRules.get();
        var settledAt = booking.requirePrepaymentSettled();
        if (!rules.correctionWindowOpen(settledAt)) {
            throw new CorrectionWindowClosedException(
                    bookingId, rules.settings().markCorrectionWindowDays());
        }
        booking.overridePrepaymentOutcome(override);
        return booking.toView(actor);
    }

    /** Предоплата получена: запись, уже взятая под блокировку строки, подтверждается с суммой. */
    private BookingView receive(BookingEntity booking, BigDecimal amount, BookingRules rules, Actor actor) {
        var now = rules.now();
        booking.confirmWithPrepayment(now, amount);
        events.confirmed(booking, now);
        return booking.toView(actor);
    }

    /** Сумма больше нуля и укладывается в {@code numeric(12, 2)}; хранится с двумя знаками после запятой. */
    private static BigDecimal requireValidAmount(@Nullable BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new ValidationException("amount", "must be positive");
        }
        var normalized = amount.stripTrailingZeros();
        if (normalized.scale() > AMOUNT_FRACTION_DIGITS) {
            throw new ValidationException("amount", "must have at most " + AMOUNT_FRACTION_DIGITS + " fraction digits");
        }
        if (normalized.precision() - normalized.scale() > AMOUNT_INTEGER_DIGITS) {
            throw new ValidationException("amount", "must have at most " + AMOUNT_INTEGER_DIGITS + " integer digits");
        }
        return amount.setScale(AMOUNT_FRACTION_DIGITS, RoundingMode.UNNECESSARY);
    }

    private boolean owesPrepaymentAndIsLive(UUID clientId) {
        return clientQueries
                .byId(clientId)
                .filter(client -> !client.erased())
                .map(ClientView::prepaymentRequired)
                .orElse(false);
    }
}
