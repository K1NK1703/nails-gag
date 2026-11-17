package gag.nails.notifications.internal.listen;

import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Свой пул асинхронных слушателей модуля ({@link NotificationsListener}): {@value #THREADS} потока — меньше пула
 * соединений с базой, поэтому всплеск событий его не займёт, — и неограниченная очередь: поток, опубликовавший событие
 * (после фиксации он ещё держит соединение), отдаёт задачу и никогда не ждёт свободного места. При остановке
 * приложения начатые и поставленные в очередь слушатели дорабатывают до {@link #AWAIT_TERMINATION}; не успевшие
 * повторятся после старта из реестра публикаций. Прочие {@code @Async} приложения идут на общем
 * {@code applicationTaskExecutor} ({@code spring.task.execution.mode: force}).
 */
@Configuration(proxyBeanMethods = false)
class ListenerExecutorConfiguration {

    /** Имя бина пула — значение {@code @Async} слушателей модуля. */
    static final String EXECUTOR = "notificationsListenerExecutor";

    static final int THREADS = 4;

    static final Duration AWAIT_TERMINATION = Duration.ofSeconds(20);

    static final String THREAD_PREFIX = "notifications-listener-";

    /** Через построитель Spring Boot: к пулу применяются общие настройщики и декоратор задач приложения. */
    @Bean(EXECUTOR)
    ThreadPoolTaskExecutor notificationsListenerExecutor(ObjectProvider<ThreadPoolTaskExecutorBuilder> builder) {
        return builder.getIfAvailable(ThreadPoolTaskExecutorBuilder::new)
                .corePoolSize(THREADS)
                .maxPoolSize(THREADS)
                .queueCapacity(Integer.MAX_VALUE)
                .allowCoreThreadTimeOut(false)
                .awaitTermination(true)
                .awaitTerminationPeriod(AWAIT_TERMINATION)
                .threadNamePrefix(THREAD_PREFIX)
                .build();
    }
}
