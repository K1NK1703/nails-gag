package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Асинхронные слушатели событий ({@code @ApplicationModuleListener} — это {@code @Async}) идут на исполнителе
 * приложения, и к нему применяются настройки {@code spring.task.execution.*} из {@code application.yaml}: не больше
 * четырёх слушателей одновременно (меньше пула соединений), при остановке начатые дорабатывают до 20 секунд.
 * Контекст — как у {@link ApplicationSmokeTest}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainers.class)
class AsyncListenersExecutorTest {

    @Autowired
    AsyncConfigurer asyncConfigurer;

    @Test
    void asyncListenersRunOnBoundedVirtualThreadExecutor() {
        var executor = asyncConfigurer.getAsyncExecutor();

        assertThat(executor).isInstanceOf(SimpleAsyncTaskExecutor.class);
        var simple = (SimpleAsyncTaskExecutor) executor;
        assertThat(simple.getConcurrencyLimit()).isEqualTo(4);
        assertThat(simple.isThrottleActive()).isTrue();
        // Срок ожидания при остановке наружу не отдаётся; его задаёт spring.task.execution.shutdown.*.
        assertThat(ReflectionTestUtils.getField(simple, "taskTerminationTimeout"))
                .isEqualTo(20_000L);
    }
}
