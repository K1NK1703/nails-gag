package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Асинхронные слушатели модуля уведомлений идут на своём пуле {@code notificationsListenerExecutor} (4 потока,
 * неограниченная очередь): у каждого слушателя событий модуля действующее {@code @Async} — с именем этого пула. Общий
 * {@code applicationTaskExecutor} при этом остаётся ({@code spring.task.execution.mode: force}) и остаётся
 * исполнителем {@code @Async} без имени. Контекст — как у {@link ApplicationSmokeTest}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainers.class)
class AsyncListenersExecutorTest {

    private static final String LISTENER_EXECUTOR = "notificationsListenerExecutor";

    @Autowired
    ApplicationContext context;

    @Autowired
    AsyncConfigurer asyncConfigurer;

    @Test
    void notificationListenersUseTheirOwnPool() {
        var listeners = notificationListeners();

        assertThat(listeners).hasSizeGreaterThanOrEqualTo(10);
        assertThat(listeners)
                .allSatisfy(method -> assertThat(AnnotatedElementUtils.findMergedAnnotation(method, Async.class))
                        .as(method.toString())
                        .isNotNull()
                        .extracting(Async::value)
                        .isEqualTo(LISTENER_EXECUTOR));
    }

    @Test
    void listenerPoolIsBoundedWithUnboundedQueue() {
        var pool = context.getBean(LISTENER_EXECUTOR, ThreadPoolTaskExecutor.class);

        assertThat(pool.getCorePoolSize()).isEqualTo(4);
        assertThat(pool.getMaxPoolSize()).isEqualTo(4);
        assertThat(pool.getQueueCapacity()).isEqualTo(Integer.MAX_VALUE);
        assertThat(pool.getThreadNamePrefix()).isEqualTo("notifications-listener-");
    }

    @Test
    void applicationTaskExecutorStaysTheDefaultForAsync() {
        assertThat(context.containsBean("applicationTaskExecutor")).isTrue();
        assertThat(asyncConfigurer.getAsyncExecutor()).isSameAs(context.getBean("applicationTaskExecutor"));
    }

    /** Методы бинов модуля уведомлений с {@code @TransactionalEventListener} (прямо или через мета-аннотацию). */
    private List<Method> notificationListeners() {
        var methods = new ArrayList<Method>();
        for (var name : context.getBeanDefinitionNames()) {
            var type = context.getType(name, false);
            if (type == null) {
                continue;
            }
            var userType = ClassUtils.getUserClass(type);
            if (!userType.getPackageName().startsWith("gag.nails.notifications")) {
                continue;
            }
            ReflectionUtils.doWithMethods(userType, method -> {
                if (AnnotatedElementUtils.hasAnnotation(method, TransactionalEventListener.class)) {
                    methods.add(method);
                }
            });
        }
        return methods;
    }
}
