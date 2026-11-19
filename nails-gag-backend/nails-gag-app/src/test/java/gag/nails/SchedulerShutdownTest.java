package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Планировщик задач по расписанию (на виртуальных потоках, {@link SimpleAsyncTaskScheduler}) при остановке приложения
 * ждёт начатые задачи до 20 с ({@code spring.task.scheduling.shutdown.*} из {@code application.yaml}): прерванный
 * прогон оставил бы итог недописанным. Без настройки {@code taskTerminationTimeout} — 0, и закрытие не ждёт никого.
 */
class SchedulerShutdownTest {

    @Test
    void schedulerAwaitsRunningTasksOnShutdown() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(Scheduling.class)
                .run(started -> assertThat(ReflectionTestUtils.getField(
                                started.getBean(SimpleAsyncTaskScheduler.class), "taskTerminationTimeout"))
                        .isEqualTo(20_000L));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling {}
}
