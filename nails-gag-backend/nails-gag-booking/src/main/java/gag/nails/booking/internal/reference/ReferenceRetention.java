package gag.nails.booking.internal.reference;

import gag.nails.common.error.InternalException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Удаление референсов с истёкшим сроком хранения пачками записей (по умолчанию по {@value #BATCH_SIZE}). Записи
 * перебираются курсором по идентификатору: страница выбирается без блокировки, затем в своей транзакции её записи
 * блокируются, условие проверяется заново, референсы и строки файлов удаляются, а файлы на диске стираются сразу
 * после фиксации. Блокировки строк держатся недолго.
 *
 * <p>Сбой пачки не останавливает проход: её записи разбираются по одной, каждая в своей транзакции; запись, на
 * которой сбой повторился, пишется в лог и ждёт следующего запуска, остальные удаляются.
 */
@Component
public class ReferenceRetention {

    /** Сколько записей обрабатывается одной транзакцией; у записи не больше трёх референсов. */
    static final int BATCH_SIZE = 100;

    /** Меньше любого идентификатора: с него начинается курсор. */
    private static final UUID FIRST = new UUID(0, 0);

    private static final Logger log = LoggerFactory.getLogger(ReferenceRetention.class);

    private final ReferenceRepository references;
    private final ReferenceRemoval removal;
    private final TransactionTemplate transaction;
    private final int batchSize;

    @Autowired
    ReferenceRetention(ReferenceRepository references, ReferenceRemoval removal, TransactionTemplate transaction) {
        this(references, removal, transaction, BATCH_SIZE);
    }

    /** С другим размером пачки и подставным удалением — для тестов пачек и сбоев. */
    ReferenceRetention(
            ReferenceRepository references, ReferenceRemoval removal, TransactionTemplate transaction, int batchSize) {
        this.references = references;
        this.removal = removal;
        this.transaction = transaction;
        this.batchSize = batchSize;
    }

    /**
     * Удаляет референсы записей, срок хранения которых истёк к {@code cutoff}
     * ({@link ReferenceRepository#findBookingsWithExpiredReferences}). Вызывать вне транзакции: пачка должна быть
     * своей транзакцией, иначе её фиксация, стирание файлов и разбор сбоя по одной записи не работают.
     *
     * @return сколько референсов удалено
     * @throws InternalException если вызвана внутри транзакции
     */
    public int removeExpired(Instant cutoff) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new InternalException("expired references must be removed outside a transaction");
        }
        var removed = 0;
        var after = FIRST;
        List<UUID> page;
        do {
            page = references.findBookingsWithExpiredReferences(cutoff, after, batchSize);
            if (page.isEmpty()) {
                break;
            }
            removed += removeBatchOrEach(cutoff, page);
            after = page.getLast();
        } while (page.size() == batchSize);
        return removed;
    }

    private int removeBatchOrEach(Instant cutoff, List<UUID> bookingIds) {
        try {
            return removeLocked(cutoff, bookingIds);
        } catch (RuntimeException e) {
            // Без стека: причину покажет error по записи, на которой сбой повторится.
            log.warn(
                    "expired references batch failed ({}), removing its {} bookings one by one",
                    e.toString(),
                    bookingIds.size());
        }
        var removed = 0;
        for (var bookingId : bookingIds) {
            try {
                removed += removeLocked(cutoff, List.of(bookingId));
            } catch (RuntimeException e) {
                log.error("removing expired references failed for booking {}", bookingId, e);
            }
        }
        return removed;
    }

    private int removeLocked(Instant cutoff, List<UUID> bookingIds) {
        return Objects.requireNonNull(
                transaction.execute(status -> removal.removeAll(references.lockExpired(bookingIds, cutoff))));
    }
}
