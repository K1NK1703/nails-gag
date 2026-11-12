package gag.nails.analytics.internal.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import gag.nails.analytics.AnalyticsModuleTest;
import gag.nails.analytics.AnalyticsReports;
import gag.nails.analytics.model.Metric;
import gag.nails.common.actor.Actor;
import gag.nails.common.time.SalonTimeZone;
import gag.nails.testsupport.MutableClock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Транзакция отчётов на рабочем пути (тесты модуля идут без тестовой транзакции): каждый отчёт сам открывает
 * транзакцию только для чтения с уровнем по умолчанию — {@code READ COMMITTED}, — и читает в ней ровно один запрос.
 * Состояние снимает шпион {@link PeriodCounts} в момент запроса: и по Spring, и по самому соединению PostgreSQL. Шпион
 * оборачивает сам объект внутри транзакционного прокси, поэтому видит ту транзакцию, в которой запрос идёт на деле.
 * Периоды пусты, данных тест не пишет. Контекст со шпионом после теста закрывается.
 */
@AnalyticsModuleTest
@DirtiesContext
class ReportsTransactionTest {

    private static final LocalDate FROM = LocalDate.of(2314, 8, 1);
    private static final LocalDate TO = LocalDate.of(2314, 8, 7);

    /** Транзакционный прокси, внутри которого — шпион. */
    @MockitoSpyBean
    PeriodCounts counts;

    @Autowired
    AnalyticsReports reports;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    SalonTimeZone salonTimeZone;

    private final List<TransactionState> states = new CopyOnWriteArrayList<>();

    /** Что видит запрос: транзакция Spring, её уровень и «только чтение», то же — у соединения базы. */
    record TransactionState(
            String method,
            boolean active,
            Integer isolation,
            boolean readOnly,
            String databaseIsolation,
            String databaseReadOnly) {}

    @BeforeEach
    void setUp() {
        clock.set(salonTimeZone.at(TO, LocalTime.NOON));
        // Заглушки — на самом шпионе: через транзакционный прокси их установка шла бы как вызов вне транзакции.
        PeriodCounts spy = AopTestUtils.getUltimateTargetObject(counts);
        doAnswer(this::recordTransaction).when(spy).keySums(any(), any());
        doAnswer(this::recordTransaction).when(spy).daySums(any(), any(), any());
    }

    @Test
    void eachReportReadsOneQueryInReadOnlyReadCommittedTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .isFalse();

        assertThat(reports.summary(FROM, TO, Actor.developer()).visitors()).isZero();
        assertThat(reports.daily(FROM, TO, Actor.developer())).hasSize(7);

        // Уровень не задан ни в Spring (null — умолчание), ни в базе: там умолчание PostgreSQL.
        assertThat(states)
                .containsExactly(
                        new TransactionState("keySums", true, null, true, "read committed", "on"),
                        new TransactionState("daySums", true, null, true, "read committed", "on"));
    }

    @Test
    void periodCountsNeedReportTransaction() {
        assertThatThrownBy(() -> counts.keySums(FROM, TO)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> counts.daySums(FROM, TO, List.of(Metric.PAGE_VIEW)))
                .isInstanceOf(IllegalTransactionStateException.class);
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
