package gag.nails.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import gag.nails.booking.BookingFinance;
import gag.nails.common.paging.Paging;
import gag.nails.finance.model.JournalFilter;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Транзакция отчётов на рабочем пути — без тестовой транзакции, внутри которой отчёт присоединился бы к чужой: каждый
 * отчёт сам открывает транзакцию только для чтения с {@code REPEATABLE READ}, и факты модуля записей читаются в ней
 * же — с тем же снимком базы. Состояние транзакции снимает шпион {@link BookingFinance} в момент вызова фактов: и по
 * Spring, и по самому соединению PostgreSQL. Периоды пусты, данных тест не создаёт и не фиксирует.
 */
@FinanceModuleTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FinanceReportsTransactionTest extends ReportsTestSupport {

    private static final LocalDate FROM = LocalDate.of(2031, 3, 2);
    private static final LocalDate TO = LocalDate.of(2031, 3, 8);

    @MockitoSpyBean
    BookingFinance bookingFinance;

    private final List<TransactionState> states = new CopyOnWriteArrayList<>();

    /** Что видит вызов фактов: транзакция Spring, её уровень и «только чтение», то же — у соединения базы. */
    record TransactionState(
            String method,
            boolean active,
            Integer isolation,
            boolean readOnly,
            String databaseIsolation,
            String databaseReadOnly) {}

    @BeforeEach
    void setUp() {
        requireReportDaysEmpty();
        reportsToday();
        doAnswer(this::recordTransaction).when(bookingFinance).visits(any(), any(), any());
        doAnswer(this::recordTransaction).when(bookingFinance).keptPrepayments(any(), any(), any());
    }

    @Test
    void reportsReadInOneReadOnlyRepeatableReadTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .isFalse();

        assertThat(reports.summary(FROM, TO, MASTER).visits().count()).isZero();
        assertThat(reports.monthly(2031, MASTER)).hasSize(12);
        assertThat(reports.services(FROM, TO, MASTER).visits()).isZero();
        assertThat(reports.journal(new JournalFilter(FROM, TO, null, null), new Paging(0, 20), MASTER)
                        .total())
                .isZero();

        // Сводка и помесячно — визиты и предоплаты, по услугам — визиты, журнал — визиты и предоплаты.
        assertThat(states)
                .extracting(TransactionState::method)
                .containsExactly(
                        "visits",
                        "keptPrepayments",
                        "visits",
                        "keptPrepayments",
                        "visits",
                        "visits",
                        "keptPrepayments");
        assertThat(states)
                .allSatisfy(state -> assertThat(state)
                        .isEqualTo(new TransactionState(
                                state.method(),
                                true,
                                Connection.TRANSACTION_REPEATABLE_READ,
                                true,
                                "repeatable read",
                                "on")));
    }

    private Object recordTransaction(InvocationOnMock invocation) throws Throwable {
        states.add(new TransactionState(
                invocation.getMethod().getName(),
                TransactionSynchronizationManager.isActualTransactionActive(),
                TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                TransactionSynchronizationManager.isCurrentTransactionReadOnly(),
                setting("transaction_isolation"),
                setting("transaction_read_only")));
        return invocation.callRealMethod();
    }

    /** Настройка текущей транзакции в базе: JDBC-запрос идёт по соединению транзакции JPA. */
    private String setting(String name) {
        return jdbc.sql("select current_setting(:name)")
                .param("name", name)
                .query(String.class)
                .single();
    }
}
