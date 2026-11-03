package gag.nails.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import gag.nails.booking.command.UpdateVisit;
import gag.nails.catalog.CatalogService;
import gag.nails.catalog.command.CreateService;
import gag.nails.catalog.command.UpdateService;
import gag.nails.catalog.model.ServiceKind;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.paging.Paged;
import gag.nails.common.paging.Paging;
import gag.nails.finance.model.CategoryTotal;
import gag.nails.finance.model.EntryKind;
import gag.nails.finance.model.JournalFilter;
import gag.nails.finance.model.JournalItem;
import gag.nails.finance.model.JournalType;
import gag.nails.finance.model.KeptPrepayments;
import gag.nails.finance.model.MonthTotals;
import gag.nails.finance.model.ServiceTotal;
import gag.nails.finance.model.VisitTotals;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Отчёты финансов ({@link FinanceReports}, спецификация финансов §4.4). Периоды — дни 2031–2032 годов, где данные
 * есть только у самого теста ({@link ReportsTestSupport}); часы стоят на 1 июня 2031, помощники визитов переводят их.
 */
@FinanceModuleTest
class FinanceReportsTest extends ReportsTestSupport {

    private static final Paging ALL = new Paging(0, Paging.MAX_SIZE);

    private static final LocalDate MAR_2 = LocalDate.of(2031, 3, 2);
    private static final LocalDate MAR_3 = LocalDate.of(2031, 3, 3);
    private static final LocalDate MAR_4 = LocalDate.of(2031, 3, 4);
    private static final LocalDate MAR_5 = LocalDate.of(2031, 3, 5);

    @Autowired
    CatalogService catalog;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    @PersistenceContext
    EntityManager entityManager;

    @BeforeEach
    void setClock() {
        reportsToday();
    }

    /** Скидка и надбавка — по каждому визиту отдельно: надбавка одного визита не гасит скидку другого. */
    @Test
    void summaryCountsVisitsDiscountsAndSurcharges() {
        visit(MAR_2, "08:00", List.of(FRENCH), "2500.00");
        visit(MAR_3, "08:00", List.of(), "0");
        visit(MAR_4, "08:00", List.of(), "3000.00");
        visit(MAR_5, "08:00");

        var summary = reports.summary(MAR_2, MAR_4, MASTER);

        assertThat(summary.from()).isEqualTo(MAR_2);
        assertThat(summary.to()).isEqualTo(MAR_4);
        assertThat(summary.visits())
                .isEqualTo(new VisitTotals(
                        3, money("7800"), money("2800"), money("500"), money("5500"), money("1833.33")));
        assertThat(summary.keptPrepayments()).isEqualTo(new KeptPrepayments(0, money("0")));
        assertThat(summary.incomeByCategory()).isEmpty();
        assertThat(summary.expenseByCategory()).isEmpty();
        assertThat(summary.income()).isEqualTo(money("5500"));
        assertThat(summary.expenses()).isEqualTo(money("0"));
        assertThat(summary.profit()).isEqualTo(money("5500"));
    }

    @Test
    void averageCheckIsZeroWithoutVisits() {
        entry(TIPS, "500", MAR_2);

        var summary = reports.summary(MAR_2, MAR_4, MASTER);
        var services = reports.services(MAR_2, MAR_4, MASTER);

        assertThat(summary.visits())
                .isEqualTo(new VisitTotals(0, money("0"), money("0"), money("0"), money("0"), money("0")));
        assertThat(services.visits()).isZero();
        assertThat(services.averageCheck()).isEqualTo(money("0"));
        assertThat(services.services()).isEmpty();
    }

    /** 100.01 / 2 = 50.005: половина копейки округляется вверх (а не к чётному). */
    @Test
    void averageCheckRoundsHalfUp() {
        visit(MAR_2, "08:00", List.of(), "100.00");
        visit(MAR_2, "12:00", List.of(), "0.01");

        assertThat(reports.summary(MAR_2, MAR_2, MASTER).visits().averageCheck())
                .isEqualTo(money("50.01"));
        assertThat(reports.services(MAR_2, MAR_2, MASTER).averageCheck()).isEqualTo(money("50.01"));
    }

    @Test
    void summaryIncludesKeptPrepayments() {
        keptPrepayment(MAR_2, "08:00", "1250");
        keptPrepayment(MAR_3, "08:00", "700.50");
        returnedPrepayment(MAR_3, "12:00", "900");
        keptPrepayment(MAR_5, "08:00", "400");

        var summary = reports.summary(MAR_2, MAR_4, MASTER);

        assertThat(summary.keptPrepayments()).isEqualTo(new KeptPrepayments(2, money("1950.50")));
        assertThat(summary.visits().count()).isZero();
        assertThat(summary.income()).isEqualTo(money("1950.50"));
        assertThat(summary.profit()).isEqualTo(money("1950.50"));
    }

    /** Категории без записей в периоде в сводке не видны; скрытые с записями — видны, с пометкой. */
    @Test
    void summaryGroupsManualEntriesByCategory() {
        var gifts = ownCategory(EntryKind.INCOME, "Подарки");
        var courses = ownCategory(EntryKind.INCOME, "Курсы");
        ownCategory(EntryKind.INCOME, "Пустая");
        var light = ownCategory(EntryKind.EXPENSE, "Свет");
        var water = ownCategory(EntryKind.EXPENSE, "Вода");
        entry(gifts, "100", MAR_2);
        entry(gifts, "50.25", MAR_4);
        entry(gifts, "999", MAR_5);
        entry(courses, "1000", MAR_3);
        entry(light, "300", MAR_3);
        entry(water, "200", MAR_2);
        categories.hide(courses, MASTER);
        categories.hide(water, MASTER);

        var summary = reports.summary(MAR_2, MAR_4, MASTER);

        assertThat(summary.incomeByCategory())
                .containsExactly(
                        new CategoryTotal(gifts, "Подарки", false, money("150.25")),
                        new CategoryTotal(courses, "Курсы", true, money("1000")));
        assertThat(summary.expenseByCategory())
                .containsExactly(
                        new CategoryTotal(light, "Свет", false, money("300")),
                        new CategoryTotal(water, "Вода", true, money("200")));
        assertThat(summary.income()).isEqualTo(money("1150.25"));
        assertThat(summary.expenses()).isEqualTo(money("500"));
        assertThat(summary.profit()).isEqualTo(money("650.25"));
    }

    @Test
    void profitIsIncomeMinusExpenses() {
        visit(MAR_2, "08:00");
        keptPrepayment(MAR_3, "08:00", "1250");
        entry(TIPS, "500", MAR_4);
        entry(RENT, "5000", MAR_4);

        var summary = reports.summary(MAR_2, MAR_4, MASTER);

        assertThat(summary.income()).isEqualTo(money("4250"));
        assertThat(summary.expenses()).isEqualTo(money("5000"));
        assertThat(summary.profit()).isEqualTo(money("-750"));
    }

    /** Пустые месяцы — нули; год 2032 заходит за предел периода (1 июня 2032) — дни после него просто нули. */
    @Test
    void monthlyHasTwelveMonths() {
        var feb29 = LocalDate.of(2032, 2, 29);
        entry(RENT, "500", feb29);
        entry(TIPS, "120", feb29);
        entry(TIPS, "30", LocalDate.of(2032, 6, 1));

        var monthly = reports.monthly(2032, MASTER);

        assertThat(monthly).hasSize(12);
        assertThat(monthly)
                .extracting(MonthTotals::month)
                .containsExactly(months(2032).toArray(YearMonth[]::new));
        assertThat(monthly.get(1))
                .isEqualTo(new MonthTotals(YearMonth.of(2032, 2), money("120"), money("500"), money("-380")));
        assertThat(monthly.get(5))
                .isEqualTo(new MonthTotals(YearMonth.of(2032, 6), money("30"), money("0"), money("30")));
        for (var month : List.of(0, 2, 3, 4, 6, 7, 8, 9, 10, 11)) {
            assertThat(monthly.get(month))
                    .isEqualTo(new MonthTotals(YearMonth.of(2032, month + 1), money("0"), money("0"), money("0")));
        }
    }

    /**
     * Визит в 00:30 1 апреля по Москве — это 31 марта по UTC, неявка в 01:30 1 мая — 30 апреля по UTC: месяц — по дню
     * салона. Ручная запись — по своему дню.
     */
    @Test
    void monthlyUsesSalonDays() {
        visit(LocalDate.of(2031, 4, 1), "00:30");
        keptPrepayment(LocalDate.of(2031, 5, 1), "00:30", "700");
        var mar31 = LocalDate.of(2031, 3, 31);
        entry(TIPS, "100", mar31);
        entry(RENT, "300", mar31);

        var monthly = reports.monthly(2031, MASTER);

        assertThat(monthly.get(2))
                .isEqualTo(new MonthTotals(YearMonth.of(2031, 3), money("100"), money("300"), money("-200")));
        assertThat(monthly.get(3))
                .isEqualTo(new MonthTotals(YearMonth.of(2031, 4), money("2500"), money("0"), money("2500")));
        assertThat(monthly.get(4))
                .isEqualTo(new MonthTotals(YearMonth.of(2031, 5), money("700"), money("0"), money("700")));
    }

    @Test
    void monthlyYearIsBounded() {
        assertThat(reports.monthly(2000, MASTER)).hasSize(12);
        assertThat(reports.monthly(2032, MASTER)).hasSize(12);

        for (var year : List.of(1999, 2033, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
            assertInvalid(() -> reports.monthly(year, MASTER), "year");
        }
    }

    /**
     * Считается фактический состав, а не выбранный при записи; сумма по прайсу — цены из записей, скидка визита на
     * услуги не делится. Название — из записи самого позднего визита периода, даже если её внесли раньше.
     */
    @Test
    void servicesReportCountsActualServices() {
        var gel = catalog.create(
                        new CreateService(
                                ServiceKind.ADDON, "Гель A", "", new BigDecimal("1000"), null, "Дизайн", false, false),
                        STAFF)
                .id();
        visit(MAR_3, "08:00", List.of(gel), "3000");
        catalog.update(
                gel, new UpdateService("Гель B", "", new BigDecimal("1200"), null, "Дизайн", false, false), STAFF);
        var earlier = completed(client("Анна", null), MAR_2, "08:00", List.of(DESIGN));
        marks.updateVisit(
                earlier.id(), new UpdateVisit(MANICURE, List.of(gel, FRENCH), new BigDecimal("4000"), null), STAFF);

        var report = reports.services(MAR_2, MAR_3, MASTER);

        assertThat(report.from()).isEqualTo(MAR_2);
        assertThat(report.to()).isEqualTo(MAR_3);
        assertThat(report.visits()).isEqualTo(2);
        assertThat(report.averageCheck()).isEqualTo(money("3500"));
        assertThat(report.services())
                .containsExactly(
                        new ServiceTotal(MANICURE, ServiceKind.MAIN, "Маникюр", 2, money("5000")),
                        new ServiceTotal(gel, ServiceKind.ADDON, "Гель A", 2, money("2200")),
                        new ServiceTotal(FRENCH, ServiceKind.ADDON, "Френч", 1, money("300")));
    }

    /**
     * Новые дни первыми; внутри дня — визиты (позже начавшиеся первыми), удержанные предоплаты, ручные записи обоих
     * видов (позже внесённые первыми) — независимо от того, в каком порядке их внесли.
     */
    @Test
    void journalOrdersByDayDescAndKind() {
        var light = ownCategory(EntryKind.EXPENSE, "Свет");
        var laterDayEntry = entry(TIPS, "100", MAR_3);
        var laterDayKept = keptPrepayment(MAR_3, "16:00", "700");
        var laterDayVisit = visit(MAR_3, "08:00");
        var kept = keptPrepayment(MAR_2, "18:00", "1250");
        var lateVisit = visit(MAR_2, "13:00", List.of(), "2000");
        var earlyVisit = visit(MAR_2, "08:00");
        var income = entry(TIPS, "300", MAR_2);
        tick();
        var expense = entry(light, "50", MAR_2);

        var journal = reports.journal(new JournalFilter(MAR_2, MAR_3, null, null), ALL, MASTER);

        assertThat(journal.total()).isEqualTo(8);
        assertThat(journal.items())
                .containsExactly(
                        visitItem(MAR_3, "2500", laterDayVisit.id()),
                        keptItem(MAR_3, "700", laterDayKept.id()),
                        entryItem(JournalType.INCOME, MAR_3, "100", "Чаевые", TIPS, laterDayEntry),
                        visitItem(MAR_2, "2000", lateVisit.id()),
                        visitItem(MAR_2, "2500", earlyVisit.id()),
                        keptItem(MAR_2, "1250", kept.id()),
                        entryItem(JournalType.EXPENSE, MAR_2, "50", "Свет", light, expense),
                        entryItem(JournalType.INCOME, MAR_2, "300", "Чаевые", TIPS, income));
    }

    @Test
    void journalFiltersByTypeAndCategory() {
        var gifts = ownCategory(EntryKind.INCOME, "Подарки");
        var visit = visit(MAR_2, "08:00");
        var kept = keptPrepayment(MAR_2, "12:00", "1250");
        var gift = entry(gifts, "100", MAR_2);
        tick();
        var tip = entry(TIPS, "200", MAR_2);
        tick();
        var rent = entry(RENT, "300", MAR_2);

        assertThat(journalIds(Set.of(JournalType.VISIT), null)).containsExactly(visit.id());
        assertThat(journalIds(Set.of(JournalType.KEPT_PREPAYMENT, JournalType.EXPENSE), null))
                .containsExactly(kept.id(), rent);
        assertThat(journalIds(Set.of(JournalType.INCOME), null)).containsExactly(tip, gift);
        assertThat(journalIds(Set.of(), null)).containsExactly(visit.id(), kept.id(), rent, tip, gift);
        assertThat(journalIds(null, gifts)).containsExactly(gift);
        assertThat(journalIds(Set.of(JournalType.VISIT, JournalType.INCOME), gifts))
                .containsExactly(gift);
        assertThat(journalIds(Set.of(JournalType.VISIT), gifts)).isEmpty();
        assertThat(journalIds(Set.of(JournalType.EXPENSE), gifts)).isEmpty();
    }

    @Test
    void journalPages() {
        visit(MAR_2, "08:00");
        visit(MAR_3, "08:00");
        keptPrepayment(MAR_3, "12:00", "1250");
        entry(TIPS, "100", MAR_2);
        tick();
        entry(RENT, "200", MAR_4);
        var filter = new JournalFilter(MAR_2, MAR_4, null, null);
        var all = reports.journal(filter, ALL, MASTER).items();

        var pages = new ArrayList<JournalItem>();
        for (var page = 0; page < 3; page++) {
            var result = reports.journal(filter, new Paging(page, 2), MASTER);
            assertThat(result.total()).isEqualTo(5);
            assertThat(result.page()).isEqualTo(page);
            assertThat(result.size()).isEqualTo(2);
            assertThat(result.items()).hasSize(page < 2 ? 2 : 1);
            pages.addAll(result.items());
        }

        assertThat(all).hasSize(5);
        assertThat(pages).isEqualTo(all);
        assertThat(reports.journal(filter, new Paging(3, 2), MASTER))
                .isEqualTo(new Paged<JournalItem>(List.of(), 3, 2, 5));
    }

    @Test
    void journalHidesClient() {
        var client = client("Аглая", "+79990001122");
        completed(client, MAR_2, "08:00", List.of());
        keptPrepayment(client, MAR_3, "08:00", "1250");

        var journal = reports.journal(new JournalFilter(MAR_2, MAR_3, null, null), ALL, MASTER);

        assertThat(journal.items())
                .extracting(JournalItem::type, JournalItem::title)
                .containsExactly(
                        tuple(JournalType.KEPT_PREPAYMENT, "Удержанная предоплата"),
                        tuple(JournalType.VISIT, "Визит: Маникюр"));
        assertThat(journal.items())
                .allSatisfy(
                        item -> assertThat(item.toString()).doesNotContain("Аглая", "79990001122", client.toString()));
    }

    @Test
    void periodLongerThan366DaysIsRejected() {
        var from = LocalDate.of(2031, 1, 1);
        var longestTo = from.plusDays(365);
        var tooLong = longestTo.plusDays(1);

        assertThat(reports.summary(from, longestTo, MASTER).visits().count()).isZero();
        assertThat(reports.services(from, longestTo, MASTER).visits()).isZero();
        assertThat(reports.journal(new JournalFilter(from, longestTo, null, null), ALL, MASTER)
                        .total())
                .isZero();
        assertInvalid(() -> reports.summary(from, tooLong, MASTER), "to");
        assertInvalid(() -> reports.services(from, tooLong, MASTER), "to");
        assertInvalid(() -> reports.journal(new JournalFilter(from, tooLong, null, null), ALL, MASTER), "to");
        assertInvalid(() -> reports.summary(MAR_3, MAR_2, MASTER), "to");
        assertInvalid(() -> reports.summary(LocalDate.of(1999, 12, 31), MAR_2, MASTER), "from");
        assertInvalid(() -> reports.journal(new JournalFilter(null, MAR_2, null, null), ALL, MASTER), "from");
    }

    @Test
    void reportsNeedFinanceRead() {
        var visit = visit(MAR_2, "08:00");

        for (var reader : List.of(MASTER, DEVELOPER, ACCOUNTANT)) {
            assertThat(reports.summary(MAR_2, MAR_2, reader).visits().count()).isEqualTo(1);
            assertThat(reports.monthly(2031, reader)).hasSize(12);
            assertThat(reports.services(MAR_2, MAR_2, reader).visits()).isEqualTo(1);
            assertThat(reports.journal(new JournalFilter(MAR_2, MAR_2, null, null), ALL, reader)
                            .items())
                    .extracting(JournalItem::bookingId)
                    .containsExactly(visit.id());
        }
        var bookingsOnly = Actor.of(null, null, Map.of(Section.BOOKINGS, AccessLevel.WRITE));
        for (var stranger : List.of(CLIENT, Actor.client(visit.clientId()), bookingsOnly)) {
            assertThatThrownBy(() -> reports.summary(MAR_2, MAR_2, stranger)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> reports.monthly(2031, stranger)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> reports.services(MAR_2, MAR_2, stranger))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> reports.journal(new JournalFilter(MAR_2, MAR_2, null, null), ALL, stranger))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    /**
     * Число SQL-операторов отчёта не зависит от числа визитов, предоплат и записей: каждый источник читается одним
     * запросом. Считает статистика Hibernate, включённая только на время замера; контекст JPA перед замером
     * очищается, иначе уже загруженные записи скрыли бы дозагрузку.
     */
    @Test
    void reportsReadEachSourceOnce() {
        var gifts = ownCategory(EntryKind.INCOME, "Подарки");
        visit(MAR_2, "08:00", List.of(FRENCH), null);
        keptPrepayment(MAR_2, "12:00", "1250");
        entry(gifts, "100", MAR_2);
        var filter = new JournalFilter(MAR_2, MAR_3, null, null);
        var summary = statements(() -> reports.summary(MAR_2, MAR_3, MASTER));
        var monthly = statements(() -> reports.monthly(2031, MASTER));
        var services = statements(() -> reports.services(MAR_2, MAR_3, MASTER));
        var journal = statements(() -> reports.journal(filter, ALL, MASTER));

        visit(MAR_3, "08:00", List.of(DESIGN), null);
        visit(MAR_3, "12:00");
        keptPrepayment(MAR_3, "16:00", "700");
        entry(gifts, "200", MAR_3);
        entry(RENT, "300", MAR_3);
        entry(LEARNING, "400", MAR_3);

        assertThat(reports.journal(filter, ALL, MASTER).total()).isEqualTo(9);
        assertThat(statements(() -> reports.summary(MAR_2, MAR_3, MASTER))).isEqualTo(summary);
        assertThat(statements(() -> reports.monthly(2031, MASTER))).isEqualTo(monthly);
        assertThat(statements(() -> reports.services(MAR_2, MAR_3, MASTER))).isEqualTo(services);
        assertThat(statements(() -> reports.journal(filter, ALL, MASTER))).isEqualTo(journal);
        assertThat(List.of(summary, monthly, services, journal))
                .allSatisfy(count -> assertThat(count).isBetween(1L, 3L));
    }

    private List<UUID> journalIds(@Nullable Set<JournalType> types, @Nullable UUID categoryId) {
        return reports.journal(new JournalFilter(MAR_2, MAR_2, types, categoryId), ALL, MASTER).items().stream()
                .map(item -> item.entryId() != null ? item.entryId() : item.bookingId())
                .toList();
    }

    private static JournalItem visitItem(LocalDate day, String amount, UUID bookingId) {
        return new JournalItem(JournalType.VISIT, day, money(amount), "Визит: Маникюр", null, bookingId, null);
    }

    private static JournalItem keptItem(LocalDate day, String amount, UUID bookingId) {
        return new JournalItem(
                JournalType.KEPT_PREPAYMENT, day, money(amount), "Удержанная предоплата", null, bookingId, null);
    }

    private static JournalItem entryItem(
            JournalType type, LocalDate day, String amount, String title, UUID categoryId, UUID entryId) {
        return new JournalItem(type, day, money(amount), title, categoryId, null, entryId);
    }

    private static List<YearMonth> months(int year) {
        var months = new ArrayList<YearMonth>();
        for (var month = 1; month <= 12; month++) {
            months.add(YearMonth.of(year, month));
        }
        return months;
    }

    /** Сумма в рублях с копейками — в том виде, в каком её отдают отчёты (два знака после запятой). */
    private static BigDecimal money(String amount) {
        return new BigDecimal(amount).setScale(2);
    }

    private long statements(Supplier<?> call) {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        entityManager.flush();
        entityManager.clear();
        var wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            call.get();
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
        }
    }
}
