package gag.nails;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * <p>Ошибка слушателя не проходит молча: неудачные ({@code FAILED}) публикации не ждутся — их повторяют рестарт и
 * задача раз в 5 минут ({@code EventPublicationRetries}, в тестах выключена вместе с задачами модуля), — но если за тест
 * появились новые (id нет среди неудачных перед тестом), тест падает с их перечнем (слушатель и тип события).
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

    private static final String FAILED = """
            select id, listener_id, event_type from modulith.event_publication where status = 'FAILED'""";

    /** Атрибут контекста теста: id неудачных публикаций перед тестом. */
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
            testContext.setAttribute(
                    FAILED_BEFORE, Set.copyOf(failed(dataSource).keySet()));
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
        if (dataSource != null && testContext.removeAttribute(FAILED_BEFORE) instanceof Set<?> before) {
            var failedNow = failed(dataSource);
            failedNow.keySet().removeAll(before);
            if (!failedNow.isEmpty()) {
                throw new AssertionError(
                        failedNow.size() + " event publications failed during the test: " + failedNow.values());
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
            var inProgress = inProgress(dataSource);
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

    /** Обрабатываемые публикации; без таблицы реестра — ноль, ошибка базы — ошибка теста. */
    private static long inProgress(DataSource dataSource) {
        return query(dataSource, IN_PROGRESS, rows -> rows.next() ? rows.getLong(1) : 0L, 0L);
    }

    /** Неудачные публикации: id → слушатель и тип события; без таблицы реестра — пусто. */
    private static Map<UUID, String> failed(DataSource dataSource) {
        return query(
                dataSource,
                FAILED,
                rows -> {
                    var failed = new LinkedHashMap<UUID, String>();
                    while (rows.next()) {
                        failed.put(
                                rows.getObject("id", UUID.class),
                                rows.getString("listener_id") + " <- " + rows.getString("event_type"));
                    }
                    return failed;
                },
                new LinkedHashMap<>());
    }

    /**
     * Запрос к реестру на отдельном соединении; без таблицы реестра — {@code absent}. Сломанный запрос или закрытый
     * пул не должны молча выключать ожидание и проверку: ошибка базы — ошибка теста.
     */
    private static <T> T query(DataSource dataSource, String sql, ResultReader<T> reader, T absent) {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            try (var exists = statement.executeQuery("select to_regclass('modulith.event_publication') is not null")) {
                if (!exists.next() || !exists.getBoolean(1)) {
                    return absent;
                }
            }
            try (var rows = statement.executeQuery(sql)) {
                return reader.read(rows);
            }
        } catch (SQLException e) {
            throw new AssertionError("cannot query event publications: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface ResultReader<T> {
        T read(ResultSet rows) throws SQLException;
    }
}
