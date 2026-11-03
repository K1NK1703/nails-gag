package gag.nails.finance;

import gag.nails.booking.BookingPrepayments;
import gag.nails.booking.BookingService;
import gag.nails.booking.VisitMarks;
import gag.nails.booking.command.CreateBooking;
import gag.nails.booking.command.UpdateVisit;
import gag.nails.booking.model.BookingView;
import gag.nails.clients.ClientService;
import gag.nails.clients.command.CreateClient;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.finance.model.EntryKind;
import gag.nails.schedule.ScheduleService;
import gag.nails.schedule.model.SlotView;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Данные отчётов: визиты и предоплаты — через публичные API модулей записей, расписания и клиентов (как в тестах
 * записей), ручные записи — через {@link FinanceEntries}. Отчёты смотрят периоды 2031–2032 годов: туда не пишет ни
 * один другой тест, поэтому суммы периода — только данные теста (каждый тест к тому же откатывается).
 */
abstract class ReportsTestSupport extends FinanceTestSupport {

    /** Услуги из seed каталога: основная «Маникюр» за 2500 и дополнения «Френч» за 300 и «Втирка / дизайн» за 200. */
    static final UUID MANICURE = UUID.fromString("019a0c00-0000-7000-8000-000000000001");

    static final UUID FRENCH = UUID.fromString("019a0c00-0000-7000-8000-000000000002");
    static final UUID DESIGN = UUID.fromString("019a0c00-0000-7000-8000-000000000003");

    /** Сотрудник записей, расписания, клиентов и прайса — встроенный мастер. */
    static final Actor STAFF = Actor.master();

    /** Только чтение «Финансов». */
    static final Actor ACCOUNTANT = Actor.of(null, null, Map.of(Section.FINANCE, AccessLevel.READ));

    /** Сегодня в поясе салона на время тестов отчётов: отчёты могут смотреть до 1 июня 2032 включительно. */
    static final LocalDate REPORTS_TODAY = LocalDate.of(2031, 6, 1);

    private static final ZoneId SALON = ZoneId.of("Europe/Moscow");

    private static final AtomicInteger CLIENTS = new AtomicInteger();

    @Autowired
    FinanceReports reports;

    @Autowired
    ScheduleService schedule;

    @Autowired
    BookingService bookings;

    @Autowired
    VisitMarks marks;

    @Autowired
    BookingPrepayments prepayments;

    @Autowired
    ClientService clients;

    /** Часы — на 09:00 салона в {@link #REPORTS_TODAY}. */
    void reportsToday() {
        clock.set(REPORTS_TODAY.atTime(LocalTime.of(9, 0)).atZone(SALON).toInstant());
    }

    /** Новый клиент без аккаунта; имя различает клиентов в выводе упавшего теста. */
    UUID client(String firstName, @Nullable String phone) {
        return clients.create(new CreateClient(firstName, null, null, phone, null, null), STAFF)
                .id();
    }

    /** Выполненный маникюр без дополнений, итог — по прайсу (2500). */
    BookingView visit(LocalDate day, String time) {
        return visit(day, time, List.of(), null);
    }

    /** Выполненный маникюр с дополнениями; итог — по прайсу, если {@code finalAmount} пуст. */
    BookingView visit(LocalDate day, String time, List<UUID> addons, @Nullable String finalAmount) {
        var booking = completed(client("Клиент " + CLIENTS.incrementAndGet(), null), day, time, addons);
        if (finalAmount == null) {
            return booking;
        }
        return marks.updateVisit(
                booking.id(), new UpdateVisit(MANICURE, addons, new BigDecimal(finalAmount), null), STAFF);
    }

    /**
     * Выполненный визит клиента: мастер записывает его на слот, часы переходят на час после начала слота, визит
     * отмечается по прайсу. Фактический состав — выбранный при записи.
     */
    BookingView completed(UUID clientId, LocalDate day, String time, List<UUID> addons) {
        var slot = slot(day, time);
        clock.set(slot.startsAt().plus(Duration.ofHours(1)));
        var booking =
                bookings.createByMaster(new CreateBooking(clientId, slot.id(), MANICURE, addons, null), false, STAFF);
        return marks.complete(booking.id(), STAFF);
    }

    /** Удержанная предоплата: неявка отмечена через час после начала слота — в тот же день салона. */
    BookingView keptPrepayment(LocalDate day, String time, String amount) {
        return keptPrepayment(client("Клиент " + CLIENTS.incrementAndGet(), null), day, time, amount);
    }

    BookingView keptPrepayment(UUID clientId, LocalDate day, String time, String amount) {
        var slot = slot(day, time);
        var booking = receivedBefore(clientId, slot, amount);
        clock.set(slot.startsAt().plus(Duration.ofHours(1)));
        return marks.markNoShow(booking.id(), STAFF);
    }

    /** Возвращённая предоплата: мастер отменил запись после получения предоплаты. */
    BookingView returnedPrepayment(LocalDate day, String time, String amount) {
        var slot = slot(day, time);
        var booking = receivedBefore(client("Клиент " + CLIENTS.incrementAndGet(), null), slot, amount);
        return bookings.cancel(booking.id(), STAFF);
    }

    /** Ручная запись в день {@code day}. */
    UUID entry(UUID categoryId, String amount, LocalDate day) {
        return add(categoryId, amount, day, null).id();
    }

    UUID ownCategory(EntryKind kind, String name) {
        return category(kind, name).id();
    }

    private SlotView slot(LocalDate day, String time) {
        return schedule.addSlot(day, LocalTime.parse(time), STAFF);
    }

    /** За сутки до слота мастер записывает клиента с ожиданием предоплаты, предоплата получена. */
    private BookingView receivedBefore(UUID clientId, SlotView slot, String amount) {
        clock.set(slot.startsAt().minus(Duration.ofDays(1)));
        var booking =
                bookings.createByMaster(new CreateBooking(clientId, slot.id(), MANICURE, List.of(), null), true, STAFF);
        return prepayments.markReceived(booking.id(), new BigDecimal(amount), STAFF);
    }
}
