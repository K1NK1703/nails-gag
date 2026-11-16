package gag.nails;

import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
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
 * <p>Видны только зафиксированные публикации: счёт идёт на отдельном соединении пула, мимо транзакции теста.
 * Неудачные ({@code FAILED}) не ждутся — их повторяет только рестарт. Без загруженного контекста, без
 * {@link DataSource} или без таблицы реестра слушатель ничего не делает.
 */
public final class EventPublicationsDrain implements TestExecutionListener, Ordered {

    static final Duration WAIT = Duration.ofSeconds(20);

    private static final String IN_PROGRESS = """
            select count(*) from modulith.event_publication
            where completion_date is null and coalesce(status, '') <> 'FAILED'""";

    /** После транзакционного слушателя (4000): транзакция теста уже завершена, контекст ещё не сброшен (3000). */
    @Override
    public int getOrder() {
        return 3500;
    }

    /** До {@code @AfterEach}: уборка теста не встречается с его же слушателями. */
    @Override
    public void afterTestExecution(TestContext testContext) throws Exception {
        drain(testContext);
    }

    /** После {@code @AfterEach}: события, опубликованные уборкой, тоже обработаны до следующего теста. */
    @Override
    public void afterTestMethod(TestContext testContext) throws Exception {
        drain(testContext);
    }

    private static void drain(TestContext testContext) throws InterruptedException {
        if (!testContext.hasApplicationContext()) {
            return;
        }
        var dataSource = testContext
                .getApplicationContext()
                .getBeanProvider(DataSource.class)
                .getIfUnique();
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

    /** Обрабатываемые публикации; без таблицы реестра — ноль. */
    private static long inProgress(DataSource dataSource) {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            try (var exists = statement.executeQuery("select to_regclass('modulith.event_publication') is not null")) {
                if (!exists.next() || !exists.getBoolean(1)) {
                    return 0;
                }
            }
            try (var count = statement.executeQuery(IN_PROGRESS)) {
                return count.next() ? count.getLong(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }
}
