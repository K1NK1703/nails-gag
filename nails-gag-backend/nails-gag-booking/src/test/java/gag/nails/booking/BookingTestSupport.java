package gag.nails.booking;

import gag.nails.clients.ClientQueries;
import gag.nails.clients.ClientService;
import gag.nails.clients.command.CreateClient;
import gag.nails.clients.command.RecordConsent;
import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentSource;
import gag.nails.common.actor.Actor;
import gag.nails.schedule.ScheduleQueries;
import gag.nails.schedule.ScheduleService;
import gag.nails.schedule.SlotView;
import gag.nails.testsupport.MutableClock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Подготовка данных через публичные API соседних модулей. Часы тестов стоят на четверге 1 октября
 * 2026, 09:00 по Москве (06:00 UTC); слоты по умолчанию открываются на следующей неделе.
 */
abstract class BookingTestSupport {

    /** Услуги из seed каталога: основная за 2500 и два дополнения за 300 и 200. */
    static final UUID MANICURE = UUID.fromString("019a0c00-0000-7000-8000-000000000001");

    static final UUID FRENCH = UUID.fromString("019a0c00-0000-7000-8000-000000000002");
    static final UUID DESIGN = UUID.fromString("019a0c00-0000-7000-8000-000000000003");

    static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);

    /** Версия политики и оферты из seed модуля clients. */
    private static final String DOCUMENTS_VERSION = "2026-09-25";

    @Autowired
    ClientService clients;

    @Autowired
    ClientQueries clientQueries;

    @Autowired
    ScheduleService schedule;

    @Autowired
    ScheduleQueries scheduleQueries;

    @Autowired
    BookingService bookings;

    @Autowired
    BookingQueries queries;

    @Autowired
    MutableClock clock;

    final Actor master = Actor.master();

    /** Клиент с согласиями на действующие политику и оферту — может записываться сам. */
    UUID client(String firstName) {
        var id = clientWithoutConsents(firstName);
        consent(id, ConsentKind.PERSONAL_DATA);
        consent(id, ConsentKind.OFFER);
        return id;
    }

    UUID clientWithoutConsents(String firstName) {
        return clients.create(new CreateClient(firstName, null, null, null, null, null), master)
                .id();
    }

    /** Клиент с признаком обязательной предоплаты. */
    UUID clientOwingPrepayment(String firstName) {
        var id = client(firstName);
        clients.setPrepaymentRequired(id, true, null, master);
        return id;
    }

    boolean owesPrepayment(UUID clientId) {
        return clientQueries.get(clientId, master).prepaymentRequired();
    }

    SlotView slot(LocalDate day, String time) {
        return schedule.addSlot(day, LocalTime.parse(time), master);
    }

    static CreateBooking manicure(UUID clientId, SlotView slot) {
        return new CreateBooking(clientId, slot.id(), MANICURE, List.of(), null);
    }

    /** Клиент записывается сам на маникюр без дополнений. */
    BookingView book(UUID clientId, SlotView slot) {
        return bookings.createByClient(manicure(clientId, slot), Actor.client(clientId));
    }

    /** Ставит часы на момент за {@code before} до начала слота. */
    void timeIsBefore(SlotView slot, Duration before) {
        clock.set(slot.startsAt().minus(before));
    }

    /** Ставит часы на момент через {@code after} после начала слота. */
    void timeIsAfterStartOf(SlotView slot, Duration after) {
        clock.set(slot.startsAt().plus(after));
    }

    private void consent(UUID clientId, ConsentKind kind) {
        clients.recordConsent(
                clientId, new RecordConsent(kind, DOCUMENTS_VERSION, ConsentSource.ENTERED_BY_MASTER), master);
    }
}
