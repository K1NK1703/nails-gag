package gag.nails.notifications.internal.telegram;

import gag.nails.notifications.internal.Failures;
import gag.nails.notifications.internal.telegram.TelegramApi.Update;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Приём сообщений боту (спека §3.6): один виртуальный поток в цикле долгого опроса {@code getUpdates}; каждое
 * сообщение — {@link TelegramUpdates}. Смещение — в памяти: после рестарта Telegram отдаёт неподтверждённые обновления
 * снова, их повтор безопасен. Работает, только если бот настроен; бин есть, только если включены задачи модуля.
 *
 * <ul>
 *   <li>Сбой опроса — пауза {@link #PAUSES} (5, 10, 30, 60 с, дальше 60 с), WARN — не чаще раза в {@link #LOG_INTERVAL}
 *       с числом пропущенных сбоев; восстановление — INFO. 409 — отдельной строкой: этим токеном опрашивает кто-то
 *       ещё (второй экземпляр приложения) или у бота задан webhook — без вмешательства человека не пройдёт.
 *   <li>Сбой обработки сообщения (база недоступна) — тоже сбой с паузой; сообщение не подтверждается и приходит снова.
 *       После {@link #ATTEMPTS_PER_UPDATE} неудач подряд оно пропускается с WARN — иначе одно сообщение, которое не
 *       обработать никогда, остановило бы бота.
 *   <li>Остановка ({@link #stop()}) прерывает долгий опрос и паузу, но не обработку сообщения посреди транзакции (её
 *       прерывание оборвало бы соединение с базой): поток доделывает сообщение и выходит; остановка ждёт его не
 *       дольше {@link #STOP_WAIT}.
 * </ul>
 *
 * В логе — только метод Bot API, код ответа или класс сбоя, id обновления; ни токена, ни чатов, ни текстов.
 */
final class TelegramPoller implements SmartLifecycle {

    /** Паузы после сбоев подряд; после последней — она же. */
    static final List<Duration> PAUSES =
            List.of(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60));

    static final Duration LOG_INTERVAL = Duration.ofMinutes(1);
    static final Duration STOP_WAIT = Duration.ofSeconds(10);
    static final int ATTEMPTS_PER_UPDATE = 3;

    private static final int CONFLICT = 409;

    private static final Logger log = LoggerFactory.getLogger(TelegramPoller.class);

    private final TelegramApi api;
    private final Handler handler;
    private final Duration pollTimeout;
    private final Clock clock;
    private final Pause pause;

    /** Поток приёма и признак «можно прервать»: прерывается только ожидание — опрос или пауза. */
    private final Object lock = new Object();

    private @Nullable Thread thread;
    private boolean interruptible;
    private volatile boolean running;

    // Состояние цикла — только в потоке приёма.
    private @Nullable Long offset;
    private int failures;
    private long failingUpdate = -1;
    private int updateAttempts;
    private @Nullable Instant lastWarned;
    private int suppressed;

    /**
     * @param handler обработка одного обновления; исключение — сбой, обновление придёт снова
     * @param pollTimeout сколько Telegram держит {@code getUpdates} без новых сообщений
     * @param pause ожидание между попытками; прерывается остановкой
     */
    TelegramPoller(TelegramApi api, Handler handler, Duration pollTimeout, Clock clock, Pause pause) {
        this.api = api;
        this.handler = handler;
        this.pollTimeout = pollTimeout;
        this.clock = clock;
        this.pause = pause;
    }

    @Override
    public void start() {
        synchronized (lock) {
            if (running || !api.configured()) {
                return;
            }
            running = true;
            thread = Thread.ofVirtual().name("telegram-poller").start(this::run);
        }
        log.info("telegram polling started");
    }

    @Override
    public void stop() {
        Thread polling;
        synchronized (lock) {
            if (!running) {
                return;
            }
            running = false;
            polling = thread;
            if (polling != null && interruptible) {
                polling.interrupt();
            }
        }
        if (polling == null) {
            return;
        }
        try {
            if (!polling.join(STOP_WAIT)) {
                log.warn("telegram polling did not stop in {}, leaving it", STOP_WAIT);
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        log.info("telegram polling stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Поток приёма не запущен или уже закончил работу. */
    boolean terminated() {
        synchronized (lock) {
            return thread == null || !thread.isAlive();
        }
    }

    private void run() {
        while (running) {
            try {
                poll();
            } catch (RuntimeException e) {
                if (!running) {
                    return;
                }
                failed("telegram polling failed unexpectedly: " + Failures.describe(e), e);
            }
        }
    }

    /** Один опрос и обработка его обновлений. */
    private void poll() {
        List<Update> updates;
        try {
            updates = interruptibly(() -> api.getUpdates(offset, pollTimeout));
        } catch (TelegramApiException e) {
            if (running) {
                failed(describe(e), null);
            }
            return;
        }
        if (updates == null) {
            return;
        }
        for (var update : updates) {
            if (!running) {
                return;
            }
            if (!handle(update)) {
                return;
            }
        }
        recovered();
    }

    /** Обрабатывает обновление; {@code false} — сбой, обновление придёт снова после паузы. */
    private boolean handle(Update update) {
        var id = update.updateId();
        try {
            handler.handle(update);
        } catch (RuntimeException e) {
            updateAttempts = id == failingUpdate ? updateAttempts + 1 : 1;
            failingUpdate = id;
            if (updateAttempts >= ATTEMPTS_PER_UPDATE) {
                log.warn(
                        "telegram update {} skipped after {} failed attempts: {}",
                        id,
                        updateAttempts,
                        Failures.describe(e));
                confirm(id);
                return true;
            }
            offset = id;
            failed(
                    "telegram update " + id + " not handled: " + Failures.describe(e),
                    Failures.isDatabase(e) ? null : e);
            return false;
        }
        confirm(id);
        return true;
    }

    private void confirm(long updateId) {
        offset = updateId + 1;
        if (updateId == failingUpdate) {
            failingUpdate = -1;
            updateAttempts = 0;
        }
    }

    /** Сбой: WARN не чаще раза в {@link #LOG_INTERVAL} и пауза по числу сбоев подряд. */
    private void failed(String description, @Nullable Throwable failure) {
        failures++;
        var wait = PAUSES.get(Math.min(failures, PAUSES.size()) - 1);
        var now = clock.instant();
        if (lastWarned == null || !now.isBefore(lastWarned.plus(LOG_INTERVAL))) {
            var message =
                    "telegram polling failed: {}; {} more failures since the previous warning; next attempt in {} s";
            if (failure == null) {
                log.warn(message, description, suppressed, wait.toSeconds());
            } else {
                log.warn(message, description, suppressed, wait.toSeconds(), failure);
            }
            lastWarned = now;
            suppressed = 0;
        } else {
            suppressed++;
        }
        interruptibly(() -> {
            try {
                pause.sleep(wait);
            } catch (InterruptedException e) {
                // Остановка: цикл увидит её по running.
            }
            return null;
        });
    }

    private void recovered() {
        if (failures > 0) {
            log.info("telegram polling recovered after {} failures", failures);
            failures = 0;
        }
    }

    /** Код ответа и метод; 409 — с объяснением: опрашивает кто-то ещё. */
    private static String describe(TelegramApiException e) {
        if (e.status() == CONFLICT) {
            return "telegram getUpdates conflict (HTTP 409): another poller uses this bot token or a webhook is set";
        }
        return e.getMessage();
    }

    /**
     * Выполняет ожидание, которое остановка может прервать; признак прерывания после него снимается — дальше
     * обработка сообщений, прерывание которой оборвало бы соединение с базой.
     */
    private <T> @Nullable T interruptibly(Waiting<T> waiting) {
        synchronized (lock) {
            if (!running) {
                return null;
            }
            interruptible = true;
        }
        try {
            return waiting.run();
        } finally {
            synchronized (lock) {
                interruptible = false;
                Thread.interrupted();
            }
        }
    }

    /** Обработка одного обновления. */
    @FunctionalInterface
    interface Handler {
        void handle(Update update);
    }

    /** Пауза между попытками. */
    @FunctionalInterface
    interface Pause {
        Pause SLEEP = Thread::sleep;

        void sleep(Duration duration) throws InterruptedException;
    }

    @FunctionalInterface
    private interface Waiting<T> {
        @Nullable
        T run();
    }
}
