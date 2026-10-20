package gag.nails.files.internal.storage;

import gag.nails.files.error.ProcessingBusyException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Места обработки картинок ({@code nails-gag.files.max-concurrent-processing}): распакованная картинка занимает
 * десятки мегабайт, и без ограничения одновременные загрузки исчерпали бы память. Очередь честная: место получает
 * дольше всех ждущий.
 *
 * <p>Публичный внутри модуля: тест модуля занимает все места, чтобы проверить отказ «занято».
 */
@Component
public final class ProcessingSlots {

    private final Semaphore permits;
    private final Duration wait;

    ProcessingSlots(FileProperties properties) {
        this.permits = new Semaphore(properties.maxConcurrentProcessing(), true);
        this.wait = properties.processingWait();
    }

    /**
     * Занимает место, ожидая не дольше {@code nails-gag.files.processing-wait}; освобождает его {@link Slot#close()}.
     *
     * @throws ProcessingBusyException место не освободилось за время ожидания или поток прервали
     */
    public Slot acquire() {
        try {
            if (!permits.tryAcquire(wait.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new ProcessingBusyException();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessingBusyException();
        }
        return new Slot(permits);
    }

    /** Занятое место; повторное закрытие ничего не делает. */
    public static final class Slot implements AutoCloseable {

        private final Semaphore permits;
        private final AtomicBoolean released = new AtomicBoolean();

        private Slot(Semaphore permits) {
            this.permits = permits;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                permits.release();
            }
        }
    }
}
