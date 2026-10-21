package gag.nails.booking.internal.reference;

import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Удаление референсов с истёкшим сроком хранения пачками записей (по умолчанию по {@value #BATCH_SIZE}), каждая
 * пачка — в своей транзакции: блокировки строк записей держатся недолго, а файлы пачки стираются сразу после её
 * фиксации. Идёт, пока очередная пачка не окажется пустой.
 */
@Component
public class ReferenceRetention {

    /** Сколько записей обрабатывается одной транзакцией; у записи не больше трёх референсов. */
    static final int BATCH_SIZE = 100;

    private final ReferenceRepository references;
    private final ReferenceRemoval removal;
    private final TransactionTemplate transaction;
    private final int batchSize;

    @Autowired
    ReferenceRetention(ReferenceRepository references, ReferenceRemoval removal, TransactionTemplate transaction) {
        this(references, removal, transaction, BATCH_SIZE);
    }

    /** С другим размером пачки — для теста нескольких пачек. */
    ReferenceRetention(
            ReferenceRepository references, ReferenceRemoval removal, TransactionTemplate transaction, int batchSize) {
        this.references = references;
        this.removal = removal;
        this.transaction = transaction;
        this.batchSize = batchSize;
    }

    /**
     * Удаляет референсы записей, срок хранения которых истёк к {@code cutoff}
     * ({@link ReferenceRepository#lockBookingsWithExpiredReferences}).
     *
     * @return сколько референсов удалено
     */
    public int removeExpired(Instant cutoff) {
        var removed = 0;
        Batch batch;
        do {
            batch = Objects.requireNonNull(transaction.execute(status -> removeBatch(cutoff)));
            removed += batch.references();
        } while (batch.bookings() > 0);
        return removed;
    }

    private Batch removeBatch(Instant cutoff) {
        var bookingIds = references.lockBookingsWithExpiredReferences(cutoff, batchSize);
        var removed = 0;
        for (var bookingId : bookingIds) {
            removed += removal.removeAll(bookingId);
        }
        return new Batch(bookingIds.size(), removed);
    }

    /** Сколько записей попало в пачку и сколько референсов у них удалено. */
    private record Batch(int bookings, int references) {}
}
