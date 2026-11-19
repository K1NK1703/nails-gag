package gag.nails.notifications.internal;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Берут ли фоновые задачи модуля новую работу. При остановке приложения закрывается первым из жизненного цикла (фаза
 * {@link SmartLifecycle#DEFAULT_PHASE}, раньше планировщика задач): с этого момента отправка не берёт ни новых
 * сообщений, ни новых доставок, а повтор публикаций не повторяет новых. Начатая отправка дорабатывает — канал
 * ограничен таймаутами (не дольше 20 с), и её результат записывается, пока планировщик ждёт задачу при остановке
 * ({@code spring.lifecycle.timeout-per-shutdown-phase}, 30 с). Без этого прогон брал бы доставку за доставкой до
 * конца пачки, планировщик прервал бы его посреди отправки, и сообщение, ушедшее без записанного результата, после
 * аренды ушло бы повторно. Несданная работа остаётся в базе созревшей и достаётся следующему запуску.
 *
 * <p>Открыт с создания: задачи могут начаться раньше запуска жизненного цикла. {@link #start()} после
 * {@link #stop()} открывает снова (остановка и запуск контекста).
 */
@Component
public class JobsLifecycle implements SmartLifecycle {

    private volatile boolean accepting = true;

    /** Брать ли новую работу: {@code false} — приложение останавливается. */
    public boolean accepting() {
        return accepting;
    }

    @Override
    public void start() {
        accepting = true;
    }

    @Override
    public void stop() {
        accepting = false;
    }

    @Override
    public boolean isRunning() {
        return accepting;
    }
}
