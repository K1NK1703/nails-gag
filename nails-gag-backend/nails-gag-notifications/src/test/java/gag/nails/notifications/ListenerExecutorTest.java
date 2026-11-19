package gag.nails.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import gag.nails.booking.event.BookingConfirmed;
import gag.nails.notifications.internal.queue.MessageQueue;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Слушатели событий модуля работают на своём пуле {@code notificationsListenerExecutor} — 4 потока
 * {@code notifications-listener-*} с неограниченной очередью, — а не на общем исполнителе приложения. Поток слушателя
 * виден по постановке сообщения в очередь (настоящей, через шпиона).
 */
@NotificationsModuleTest
class ListenerExecutorTest extends QueueTestSupport {

    @MockitoSpyBean
    MessageQueue queue;

    @Autowired
    @Qualifier("notificationsListenerExecutor")
    ThreadPoolTaskExecutor listenerExecutor;

    @Test
    void listenersRunOnTheModulePool() {
        List<String> threads = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
                    threads.add(Thread.currentThread().getName());
                    return invocation.callRealMethod();
                })
                .when(queue)
                .enqueue(any());
        var bookingId = bookingId();
        var now = clock.instant();

        publish(bookingId, new BookingConfirmed(bookingId, UUID.randomUUID(), now, false, now));

        assertThat(threads).isNotEmpty().allSatisfy(name -> assertThat(name).startsWith("notifications-listener-"));
    }

    @Test
    void poolHasFourThreadsAndUnboundedQueue() {
        assertThat(listenerExecutor.getCorePoolSize()).isEqualTo(4);
        assertThat(listenerExecutor.getMaxPoolSize()).isEqualTo(4);
        assertThat(listenerExecutor.getQueueCapacity()).isEqualTo(Integer.MAX_VALUE);
        assertThat(listenerExecutor.getThreadNamePrefix()).isEqualTo("notifications-listener-");
    }
}
