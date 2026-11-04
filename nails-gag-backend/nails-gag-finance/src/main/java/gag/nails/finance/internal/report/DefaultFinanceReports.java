package gag.nails.finance.internal.report;

import gag.nails.booking.BookingFinance;
import gag.nails.booking.model.KeptPrepaymentFact;
import gag.nails.booking.model.VisitFact;
import gag.nails.booking.model.VisitServiceFact;
import gag.nails.catalog.model.ServiceKind;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.InternalException;
import gag.nails.common.error.ValidationException;
import gag.nails.common.paging.Paged;
import gag.nails.common.paging.Paging;
import gag.nails.common.time.ReportPeriod;
import gag.nails.common.time.SalonTimeZone;
import gag.nails.common.validation.CommandValidator;
import gag.nails.finance.FinanceReports;
import gag.nails.finance.internal.entry.CategorySum;
import gag.nails.finance.internal.entry.EntryLine;
import gag.nails.finance.internal.entry.EntryReports;
import gag.nails.finance.model.CategoryTotal;
import gag.nails.finance.model.EntryKind;
import gag.nails.finance.model.JournalFilter;
import gag.nails.finance.model.JournalItem;
import gag.nails.finance.model.JournalType;
import gag.nails.finance.model.KeptPrepayments;
import gag.nails.finance.model.MonthTotals;
import gag.nails.finance.model.ServiceTotal;
import gag.nails.finance.model.ServicesReport;
import gag.nails.finance.model.Summary;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Отчёты из двух источников: денежные факты модуля записей ({@link BookingFinance}) — не больше одного вызова каждого
 * метода на отчёт, каждый — один запрос, — и ручные записи ({@link EntryReports}): их суммы считает база агрегатами,
 * строки журнала — один запрос. Числа запросов не зависят от числа визитов и записей.
 *
 * <p>Отчёт читает несколько запросов, и они должны видеть одно состояние базы: иначе визит, отмеченный между двумя
 * запросами, попал бы в одну часть сводки и не попал в другую. Поэтому транзакция только для чтения с
 * {@code REPEATABLE READ}: в PostgreSQL все её запросы видят один снимок, а читающей транзакции этот уровень ничего не
 * стоит — ошибок сериализации у неё не бывает. Факты модуля записей читаются в этой же транзакции (их транзакция
 * присоединяется). Внутри чужой транзакции действует её уровень изоляции (Spring не меняет его у уже начатой).
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class DefaultFinanceReports implements FinanceReports {

    private static final String VISIT_TITLE = "Визит: ";
    private static final String KEPT_PREPAYMENT_TITLE = "Удержанная предоплата";

    /**
     * Порядок журнала: новые дни первыми, внутри дня — визиты, удержанные предоплаты, ручные записи. Внутри группы
     * порядок задаёт источник — сортировка устойчива ({@link List#sort}) и его не меняет.
     */
    private static final Comparator<JournalItem> JOURNAL_ORDER = Comparator.comparing(JournalItem::day)
            .reversed()
            .thenComparingInt(item -> switch (item.type()) {
                case VISIT -> 0;
                case KEPT_PREPAYMENT -> 1;
                case INCOME, EXPENSE -> 2;
            });

    /** Отчёт по услугам: основные, затем дополнения; чаще оказанные; с большей суммой; по id услуги. */
    private static final Comparator<ServiceTotal> SERVICES_ORDER = Comparator.comparing(ServiceTotal::kind)
            .thenComparing(Comparator.comparingInt(ServiceTotal::count).reversed())
            .thenComparing(Comparator.comparing(ServiceTotal::priceSum).reversed())
            .thenComparing(ServiceTotal::serviceId);

    private final BookingFinance bookings;
    private final EntryReports entries;
    private final CommandValidator validator;
    private final SalonTimeZone salonTimeZone;
    private final Clock clock;

    DefaultFinanceReports(
            BookingFinance bookings,
            EntryReports entries,
            CommandValidator validator,
            SalonTimeZone salonTimeZone,
            Clock clock) {
        this.bookings = bookings;
        this.entries = entries;
        this.validator = validator;
        this.salonTimeZone = salonTimeZone;
        this.clock = clock;
    }

    @Override
    public Summary summary(LocalDate from, LocalDate to, Actor actor) {
        actor.requireRead(Section.FINANCE);
        var period = period(from, to);
        var visits = VisitStats.totals(bookings.visits(period.from(), period.to(), actor));
        var kept = keptTotals(bookings.keptPrepayments(period.from(), period.to(), actor));
        var sums = entries.sumByCategory(period.from(), period.to());
        var incomeByCategory = totals(sums, EntryKind.INCOME);
        var expenseByCategory = totals(sums, EntryKind.EXPENSE);
        var income = visits.received().add(kept.amount()).add(sum(incomeByCategory));
        var expenses = sum(expenseByCategory);
        return new Summary(
                period.from(),
                period.to(),
                visits,
                kept,
                incomeByCategory,
                expenseByCategory,
                Money.of(income),
                Money.of(expenses),
                Money.of(income.subtract(expenses)));
    }

    /**
     * Год — период {@link ReportPeriod} с 1 января по 31 декабря, но не дальше её предела вперёд: дни после предела не
     * читаются (операций там нет — дни записей и фактов ограничены тем же пределом), их месяцы — нули. Месяц визита и
     * предоплаты — по дню факта, уже в поясе салона; месяц ручной записи — по дню операции.
     */
    @Override
    public List<MonthTotals> monthly(int year, Actor actor) {
        actor.requireRead(Section.FINANCE);
        var today = today();
        var lastDay = today.plusDays(ReportPeriod.MAX_DAYS_AHEAD);
        // Проверка до LocalDate.of: год вне диапазона LocalDate — ошибка запроса, а не сбой.
        if (year < ReportPeriod.EARLIEST.getYear()) {
            throw new ValidationException("year", "must not be before " + ReportPeriod.EARLIEST.getYear());
        }
        if (year > lastDay.getYear()) {
            throw new ValidationException("year", "must not start after " + lastDay);
        }
        var start = LocalDate.of(year, Month.JANUARY, 1);
        var end = LocalDate.of(year, Month.DECEMBER, 31);
        var period = ReportPeriod.of(start, end.isAfter(lastDay) ? lastDay : end, today);
        var income = zeros();
        var expenses = zeros();
        for (var visit : bookings.visits(period.from(), period.to(), actor)) {
            add(income, visit.day().getMonthValue(), visit.finalAmount());
        }
        for (var kept : bookings.keptPrepayments(period.from(), period.to(), actor)) {
            add(income, kept.day().getMonthValue(), kept.amount());
        }
        for (var sum : entries.sumByMonth(period.from(), period.to())) {
            if (sum.month().getYear() != year) {
                throw new InternalException("entry sum of " + sum.month() + " in the period " + period);
            }
            add(sum.kind() == EntryKind.INCOME ? income : expenses, sum.month().getMonthValue(), sum.amount());
        }
        var months = new ArrayList<MonthTotals>(12);
        for (var month = 1; month <= 12; month++) {
            var monthIncome = income[month - 1];
            var monthExpenses = expenses[month - 1];
            months.add(new MonthTotals(
                    YearMonth.of(year, month),
                    Money.of(monthIncome),
                    Money.of(monthExpenses),
                    Money.of(monthIncome.subtract(monthExpenses))));
        }
        return List.copyOf(months);
    }

    /**
     * Ключ услуги — её id и вид. Название — из записи самого позднего визита периода с этой услугой: факты идут по
     * началу визита (при равном начале — в порядке записей модуля записей), и каждое следующее название заменяет
     * прежнее.
     */
    @Override
    public ServicesReport services(LocalDate from, LocalDate to, Actor actor) {
        actor.requireRead(Section.FINANCE);
        var period = period(from, to);
        var visits = bookings.visits(period.from(), period.to(), actor);
        var services = new LinkedHashMap<ServiceKey, ServiceCounter>();
        for (var visit : visits) {
            for (var service : visit.services()) {
                var key = new ServiceKey(service.serviceId(), service.kind());
                var counter = services.get(key);
                if (counter == null) {
                    services.put(key, new ServiceCounter(service));
                } else {
                    counter.add(service);
                }
            }
        }
        var totals = services.entrySet().stream()
                .map(entry -> entry.getValue().toTotal(entry.getKey()))
                .sorted(SERVICES_ORDER)
                .toList();
        var averageCheck = VisitStats.totals(visits).averageCheck();
        return new ServicesReport(period.from(), period.to(), visits.size(), averageCheck, totals);
    }

    /**
     * Три источника — визиты, удержанные предоплаты, ручные записи — каждый уже упорядочен (факты модуля записей — по
     * моменту, их порядок разворачивается: внутри дня позже первыми; записи — запросом). Они сливаются устойчивой
     * сортировкой по дню и группе, а страница вырезается в памяти: страницу в базе не сделать, пока источники живут в
     * двух модулях. Это дёшево: у одного мастера за период не больше года — тысячи визитов и записей, и все три
     * источника читаются за период целиком так же, как для сводки.
     */
    @Override
    public Paged<JournalItem> journal(JournalFilter filter, Paging paging, Actor actor) {
        actor.requireRead(Section.FINANCE);
        validator.requireValid(filter);
        var period = ReportPeriod.of(filter.from(), filter.to(), today());
        var types = types(filter.types());
        // У визитов и предоплат категории нет: отбор по категории оставляет только ручные записи.
        var bookingsToo = filter.categoryId() == null;
        var items = new ArrayList<JournalItem>();
        if (bookingsToo && types.contains(JournalType.VISIT)) {
            bookings.visits(period.from(), period.to(), actor).reversed().stream()
                    .map(DefaultFinanceReports::visitItem)
                    .forEach(items::add);
        }
        if (bookingsToo && types.contains(JournalType.KEPT_PREPAYMENT)) {
            bookings.keptPrepayments(period.from(), period.to(), actor).reversed().stream()
                    .map(DefaultFinanceReports::keptItem)
                    .forEach(items::add);
        }
        var kinds = EnumSet.noneOf(EntryKind.class);
        if (types.contains(JournalType.INCOME)) {
            kinds.add(EntryKind.INCOME);
        }
        if (types.contains(JournalType.EXPENSE)) {
            kinds.add(EntryKind.EXPENSE);
        }
        entries.lines(period.from(), period.to(), kinds, filter.categoryId()).stream()
                .map(DefaultFinanceReports::entryItem)
                .forEach(items::add);
        items.sort(JOURNAL_ORDER);
        var first = (int) Math.min(paging.offset(), items.size());
        var last = Math.min(first + paging.size(), items.size());
        return Paged.of(items.subList(first, last), paging, items.size());
    }

    /** Не заданы или пусты — все виды. */
    private static Set<JournalType> types(@Nullable Set<JournalType> types) {
        return types == null || types.isEmpty() ? EnumSet.allOf(JournalType.class) : EnumSet.copyOf(types);
    }

    /**
     * «Визит: &lt;основная услуга&gt;» — по виду услуги, а не по месту в составе. Без данных клиента: их нет и в
     * фактах.
     *
     * @throws InternalException если в составе выполненного визита нет основной услуги — так не бывает
     */
    private static JournalItem visitItem(VisitFact visit) {
        var main = visit.services().stream()
                .filter(service -> service.kind() == ServiceKind.MAIN)
                .findFirst()
                .orElseThrow(() -> new InternalException("visit " + visit.bookingId() + " has no main service"));
        return new JournalItem(
                JournalType.VISIT,
                visit.day(),
                Money.of(visit.finalAmount()),
                VISIT_TITLE + main.title(),
                null,
                visit.bookingId(),
                null);
    }

    private static JournalItem keptItem(KeptPrepaymentFact kept) {
        return new JournalItem(
                JournalType.KEPT_PREPAYMENT,
                kept.day(),
                Money.of(kept.amount()),
                KEPT_PREPAYMENT_TITLE,
                null,
                kept.bookingId(),
                null);
    }

    /**
     * Название записи — текущее название её категории. Комментарий в журнал не идёт: это свободный текст до 500
     * символов, журнал — короткий список операций, а запись с комментарием открывается по {@code entryId}.
     */
    private static JournalItem entryItem(EntryLine line) {
        return new JournalItem(
                line.kind() == EntryKind.INCOME ? JournalType.INCOME : JournalType.EXPENSE,
                line.occurredOn(),
                Money.of(line.amount()),
                line.categoryName(),
                line.categoryId(),
                null,
                line.entryId());
    }

    private static KeptPrepayments keptTotals(List<KeptPrepaymentFact> kept) {
        var amount = kept.stream().map(KeptPrepaymentFact::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new KeptPrepayments(kept.size(), Money.of(amount));
    }

    private static List<CategoryTotal> totals(List<CategorySum> sums, EntryKind kind) {
        return sums.stream()
                .filter(sum -> sum.kind() == kind)
                .map(sum -> new CategoryTotal(sum.categoryId(), sum.name(), sum.hidden(), Money.of(sum.amount())))
                .toList();
    }

    private static BigDecimal sum(List<CategoryTotal> totals) {
        return totals.stream().map(CategoryTotal::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal[] zeros() {
        var months = new BigDecimal[12];
        Arrays.fill(months, BigDecimal.ZERO);
        return months;
    }

    private static void add(BigDecimal[] months, int month, BigDecimal amount) {
        months[month - 1] = months[month - 1].add(amount);
    }

    /**
     * Проверенный период. Пустые границы — ошибка запроса по своему полю: вызывающий код на Java может передать их
     * мимо проверок HTTP-слоя.
     */
    private ReportPeriod period(@Nullable LocalDate from, @Nullable LocalDate to) {
        if (from == null) {
            throw new ValidationException("from", "must not be null");
        }
        if (to == null) {
            throw new ValidationException("to", "must not be null");
        }
        return ReportPeriod.of(from, to, today());
    }

    private LocalDate today() {
        return salonTimeZone.today(clock);
    }

    /** Услуга отчёта: одна и та же услуга каталога — один id и один вид. */
    private record ServiceKey(UUID serviceId, ServiceKind kind) {}

    /**
     * Счётчик услуги по визитам в порядке их начала. Создаётся по первому факту услуги и сразу его учитывает; каждый
     * следующий факт ({@link #add}) заменяет название — остаётся название самого позднего визита.
     */
    private static final class ServiceCounter {

        private int count;
        private BigDecimal priceSum;
        private String title;

        ServiceCounter(VisitServiceFact first) {
            this.count = 1;
            this.priceSum = first.price();
            this.title = first.title();
        }

        void add(VisitServiceFact service) {
            count++;
            priceSum = priceSum.add(service.price());
            title = service.title();
        }

        ServiceTotal toTotal(ServiceKey key) {
            return new ServiceTotal(key.serviceId(), key.kind(), title, count, Money.of(priceSum));
        }
    }
}
