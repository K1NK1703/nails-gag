package gag.nails.notifications.internal.telegram;

import gag.nails.notifications.internal.Failures;
import gag.nails.notifications.internal.telegram.TelegramApi.Update;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Приём сообщений боту (спека §3.6): один виртуальный поток в цикле долгого опроса {@code getUpdates}; каждое
 * сообщение — {@link TelegramUpdates}. Смещение — в памяти: после рестарта Telegram отдаёт неподтверждённые
 * обновления снова, их повтор безопасен. Работает, только если бот настроен; бин есть, только если включены задачи
 * модуля.
 *
 * <ul>
 *   <li>Сбой опроса — пауза {@link #PAUSES} (5, 10, 30, 60 с, дальше 60 с), WARN — не чаще раза в
 *       {@link #LOG_INTERVAL} с числом пропущенных сбоев; восстановление — INFO. 409 — своей строкой, и первый 409
 *       подряд пишется всегда, мимо ограничения: этим токеном опрашивает кто-то ещё (второй экземпляр приложения)
 *       или у бота задан webhook — без вмешательства человека не пройдёт.
 *   <li>Сбой обработки сообщения — тоже сбой с паузой; сообщение не подтверждается и приходит снова. Временный сбой
 *       базы (нет соединения, таймаут, взаимная блокировка — {@link Failures#isTransientDatabase}) повторяется сколько
 *       угодно: иначе недоступная база «съела» бы сообщения, в том числе {@code /stop}. Прочий сбой — после
 *       {@link #ATTEMPTS_PER_UPDATE} неудач подряд сообщение пропускается с WARN: одно сообщение, которое не
 *       обработать никогда, не должно останавливать бота.
 *   <li>Остановка ({@link #stop()}) прерывает долгий опрос и паузу, но не обработку сообщения посреди транзакции (её
 *       прерывание оборвало бы соединение с базой): поток доделывает сообщение, коротким запросом подтверждает
 *       обработанные с последнего опроса (иначе после рестарта они пришли бы снова) и выходит; остановка ждёт его не
 *       дольше {@link #STOP_WAIT}. Пока прежний поток жив, новый не запускается.
 *   <li>Обновление без читаемого id (его не подтвердить, Telegram отдаст его снова сразу) — сбой с паузой после
 *       обработки остальных обновлений пачки.
 *   <li>Неожиданная ошибка ({@link Error}) останавливает приём с ERROR в логе.
 * </ul>
 *
 * В логе — только метод Bot API, код ответа или класс сбоя, id обновления; ни токена, ни чатов, ни текстов.
 */
final class TelegramPoller implements SmartLifecycle {

    /** Паузы после сбоев подряд; после последней — она же. */
    static final List<Duration> PAUSES =
            List.of(Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60));

    static final Duration LOG_INTERVAL = Duration.ofMinutes(1);

    /**
     * Сколько остановка ждёт поток: обработка сообщения и ответ бота (до {@link TelegramApi#REPLY_TIMEOUT} плюс
     * подключение), затем подтверждение обработанного.
     */
    static final Duration STOP_WAIT = Duration.ofSeconds(15);

    static final int ATTEMPTS_PER_UPDATE = 3;

    private static final int CONFLICT = 409;

    private static final Logger log = LoggerFactory.getLogger(TelegramPoller.class);

    private final TelegramApi api;
    private final Handler handler;
    private final Duration pollTimeout;
    private final Clock clock;
    private final Pause pause;
    private final Duration stopWait;

    /** Поток приёма и признак «можно прервать»: прерывается только ожидание — опрос или пауза. */
    private final Object lock = new Object();

    private @Nullable Thread thread;
    private boolean interruptible;
    private volatile boolean running;

    // Состояние цикла — только в потоке приёма (поток один: новый не запускается, пока жив прежний).
    private @Nullable Long offset;
    private @Nullable Long requestedOffset;
    private int failures;
    private long failingUpdate = -1;
    private int updateAttempts;
    private @Nullable Instant lastWarned;
    private int suppressed;
    private boolean conflict;

    /**
     * @param handler обработка одного обновления; исключение — сбой, обновление придёт снова
     * @param pollTimeout сколько Telegram держит {@code getUpdates} без новых сообщений
     * @param pause ожидание между попытками; прерывается остановкой
     */
    TelegramPoller(TelegramApi api, Handler handler, Duration pollTimeout, Clock clock, Pause pause) {
        this(api, handler, pollTimeout, clock, pause, STOP_WAIT);
    }

    /** @param stopWait сколько остановка ждёт поток приёма */
    TelegramPoller(
            TelegramApi api, Handler handler, Duration pollTimeout, Clock clock, Pause pause, Duration stopWait) {
        this.api = api;
        this.handler = handler;
        this.pollTimeout = pollTimeout;
        this.clock = clock;
        this.pause = pause;
        this.stopWait = stopWait;
    }

    @Override
    public void start() {
        synchronized (lock) {
            if (running || !api.configured()) {
                return;
            }
            if (thread != null && thread.isAlive()) {
                log.warn("telegram polling is not started: the previous polling thread has not finished yet");
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
            if (!polling.join(stopWait)) {
                log.warn("telegram polling did not stop in {} s, leaving it", stopWait.toSeconds());
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
        try {
            while (running) {
                try {
                    poll();
                } catch (RuntimeException e) {
                    if (!running) {
                        break;
                    }
                    failed("telegram polling failed unexpectedly: " + Failures.describe(e), e, false);
                }
            }
            confirmHandled();
        } catch (Throwable e) {
            running = false;
            log.error("telegram polling stopped by an unexpected error", e);
        }
    }

    /** Один опрос и обработка его обновлений. */
    private void poll() {
        TelegramApi.Batch batch;
        try {
            requestedOffset = offset;
            batch = interruptibly(() -> api.getUpdates(offset, pollTimeout));
        } catch (TelegramApiException e) {
            if (running) {
                var isConflict = e.status() == CONFLICT;
                failed(describe(e), null, isConflict && !conflict);
                conflict = isConflict;
            }
            return;
        }
        if (batch == null) {
            return;
        }
        conflict = false;
        for (var update : batch.updates()) {
            if (!running) {
                return;
            }
            if (!handle(update)) {
                return;
            }
        }
        if (batch.unreadable()) {
            // Обновление без id не подтвердить: Telegram отдаст его снова сразу же — без паузы приём крутился бы.
            failed("telegram getUpdates answer has an update without a readable id", null, false);
            return;
        }
        recovered();
    }

    /** Обрабатывает обновление; {@code false} — сбой, обновление придёт снова после паузы. */
    private boolean handle(Update update) {
        var id = update.updateId();
        try {
            handler.handle(update);
        } catch (RuntimeException e) {
            var description = "telegram update " + id + " not handled: " + Failures.describe(e);
            offset = id;
            if (Failures.isTransientDatabase(e)) {
                failed(description, null, false);
                return false;
            }
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
            failed(description, Failures.isDatabase(e) ? null : e, false);
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

    /**
     * При остановке: обработанные с последнего опроса обновления подтверждаются коротким запросом — иначе после
     * рестарта они пришли бы снова. Не вышло — не страшно: повтор безопасен.
     */
    private void confirmHandled() {
        var handled = offset;
        if (handled == null || Objects.equals(handled, requestedOffset)) {
            return;
        }
        try {
            api.confirm(handled);
        } catch (TelegramApiException e) {
            log.info("telegram updates not confirmed on stop: {}", e.getMessage());
        }
    }

    /**
     * Сбой: WARN не чаще раза в {@link #LOG_INTERVAL} (или всегда, если {@code always}) и пауза по числу сбоев
     * подряд.
     */
    private void failed(String description, @Nullable Throwable failure, boolean always) {
        failures++;
        var wait = PAUSES.get(Math.min(failures, PAUSES.size()) - 1);
        var now = clock.instant();
        if (always || lastWarned == null || !now.isBefore(lastWarned.plus(LOG_INTERVAL))) {
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
