package gag.nails;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Асинхронные слушатели событий ({@code @ApplicationModuleListener}, модуль уведомлений) не переходят границу теста:
 * после теста и после его уборки слушатель ждёт (не дольше {@link #WAIT}), пока в реестре публикаций не останется
 * обрабатываемых событий. Иначе работа слушателя, запущенная одним тестом, шла бы во время следующего — например,
 * попадала бы в счёт ожидающих блокировку запросов ({@link ConcurrencySupport}). Подключён для всех тестов модуля
 * через {@code META-INF/spring.factories}.
 *
 * <p>Ошибка слушателя не проходит молча: неудачные ({@code FAILED}) публикации не ждутся — их повторяет только
 * рестарт, — но если за тест их стало больше, чем было перед ним, тест падает с перечнем новых (слушатель и тип
 * события).
 *
 * <p>Видны только зафиксированные публикации: счёт идёт на отдельном соединении пула, мимо транзакции теста. Без
 * загруженного контекста, без {@link DataSource} или без таблицы реестра слушатель ничего не делает; ошибка запроса к
 * базе — ошибка теста.
 */
public final class EventPublicationsDrain implements TestExecutionListener, Ordered {

    static final Duration WAIT = Duration.ofSeconds(20);

    private static final String IN_PROGRESS = """
            select count(*) from modulith.event_publication
            where completion_date is null and coalesce(status, '') <> 'FAILED'""";

    private static final String FAILED = "select count(*) from modulith.event_publication where status = 'FAILED'";

    private static final String FAILED_AFTER = """
            select listener_id, event_type from modulith.event_publication
            where status = 'FAILED' order by publication_date desc, id limit ?""";

    /** Атрибут контекста теста: сколько неудачных публикаций было перед тестом. */
    private static final String FAILED_BEFORE = EventPublicationsDrain.class.getName() + ".failedBefore";

    /** После транзакционного слушателя (4000): транзакция теста уже завершена, контекст ещё не сброшен (3000). */
    @Override
    public int getOrder() {
        return 3500;
    }

    /** До {@code @BeforeEach}: неудачные публикации, оставшиеся от прежних тестов, этому тесту не в счёт. */
    @Override
    public void beforeTestMethod(TestContext testContext) {
        var dataSource = dataSource(testContext);
        if (dataSource != null) {
            testContext.setAttribute(FAILED_BEFORE, count(dataSource, FAILED));
        }
    }

    /** До {@code @AfterEach}: уборка теста не встречается с его же слушателями. */
    @Override
    public void afterTestExecution(TestContext testContext) throws Exception {
        drain(testContext);
    }

    /**
     * После {@code @AfterEach}: события, опубликованные уборкой, тоже обработаны до следующего теста; новых неудачных
     * публикаций за тест нет.
     */
    @Override
    public void afterTestMethod(TestContext testContext) throws Exception {
        drain(testContext);
        var dataSource = dataSource(testContext);
        if (dataSource != null && testContext.removeAttribute(FAILED_BEFORE) instanceof Long before) {
            var failed = count(dataSource, FAILED);
            if (failed > before) {
                throw new AssertionError((failed - before) + " event publications failed during the test: "
                        + lastFailed(dataSource, failed - before));
            }
        }
    }

    private static void drain(TestContext testContext) throws InterruptedException {
        var dataSource = dataSource(testContext);
        if (dataSource == null) {
            return;
        }
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (true) {
            var inProgress = count(dataSource, IN_PROGRESS);
            if (inProgress == 0) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError(inProgress + " event publications are still in progress after " + WAIT);
            }
            Thread.sleep(20);
        }
    }

    private static @Nullable DataSource dataSource(TestContext testContext) {
        if (!testContext.hasApplicationContext()) {
            return null;
        }
        return testContext
                .getApplicationContext()
                .getBeanProvider(DataSource.class)
                .getIfUnique();
    }

    /** Число публикаций по запросу {@code sql}; без таблицы реестра — ноль, ошибка базы — ошибка теста. */
    private static long count(DataSource dataSource, String sql) {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            try (var exists = statement.executeQuery("select to_regclass('modulith.event_publication') is not null")) {
                if (!exists.next() || !exists.getBoolean(1)) {
                    return 0;
                }
            }
            try (var count = statement.executeQuery(sql)) {
                return count.next() ? count.getLong(1) : 0;
            }
        } catch (SQLException e) {
            // Сломанный запрос или закрытый пул не должны молча выключать ожидание и проверку.
            throw new AssertionError("cannot count event publications: " + e.getMessage(), e);
        }
    }

    /** Последние {@code limit} неудачных публикаций: слушатель и тип события. */
    private static List<String> lastFailed(DataSource dataSource, long limit) {
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(FAILED_AFTER)) {
            statement.setLong(1, limit);
            var failed = new ArrayList<String>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    failed.add(rows.getString("listener_id") + " <- " + rows.getString("event_type"));
                }
            }
            return failed;
        } catch (SQLException e) {
            throw new AssertionError("cannot list failed event publications: " + e.getMessage(), e);
        }
    }
}
