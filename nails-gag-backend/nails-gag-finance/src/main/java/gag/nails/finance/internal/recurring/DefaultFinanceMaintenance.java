package gag.nails.finance.internal.recurring;

import gag.nails.common.error.InternalException;
import gag.nails.common.time.SalonTimeZone;
import gag.nails.finance.FinanceMaintenance;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Шаблоны перебираются курсором по идентификатору, каждый — в своей транзакции: строка шаблона блокируется
 * ({@code FOR UPDATE SKIP LOCKED}), записи за наступившие дни создаются, прогресс сдвигается, транзакция
 * фиксируется. Два одновременных запуска не создают двух записей за день: шаблон обрабатывает тот, кто взял его
 * строку, второй его пропускает или, придя после фиксации, видит сдвинутый прогресс; уникальность
 * {@code (recurring_id, occurred_on)} со вставкой {@code ON CONFLICT DO NOTHING} — последняя защита.
 */
@Service
class DefaultFinanceMaintenance implements FinanceMaintenance {

    /** Меньше любого идентификатора: с него начинается курсор. */
    private static final UUID FIRST = new UUID(0, 0);

    private static final Logger log = LoggerFactory.getLogger(DefaultFinanceMaintenance.class);

    private final RecurringRepository recurring;
    private final RecurringSchedule schedule;
    private final TransactionTemplate transaction;
    private final SalonTimeZone salonTimeZone;
    private final Clock clock;

    DefaultFinanceMaintenance(
            RecurringRepository recurring,
            RecurringSchedule schedule,
            TransactionTemplate transaction,
            SalonTimeZone salonTimeZone,
            Clock clock) {
        this.recurring = recurring;
        this.schedule = schedule;
        this.transaction = transaction;
        this.salonTimeZone = salonTimeZone;
        this.clock = clock;
    }

    @Override
    public int createDueRecurringEntries() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new InternalException("recurring entries must be created outside a transaction");
        }
        var today = salonTimeZone.today(clock);
        var created = 0;
        var after = FIRST;
        while (true) {
            var current = new AtomicReference<UUID>();
            var cursor = after;
            try {
                created += Objects.requireNonNull(transaction.execute(status -> {
                    var id = recurring.lockNextDue(cursor, today);
                    if (id.isEmpty()) {
                        return 0;
                    }
                    current.set(id.get());
                    var template = recurring
                            .findById(id.get())
                            .orElseThrow(() -> new InternalException("locked template " + id.get() + " is missing"));
                    return schedule.createDue(template, today);
                }));
            } catch (RuntimeException e) {
                if (current.get() == null) {
                    // Сбой самого выбора шаблона: продолжать не с чего, следующий запуск попробует снова.
                    throw e;
                }
                log.error("creating entries of recurring template {} failed", current.get(), e);
            }
            if (current.get() == null) {
                return created;
            }
            after = current.get();
        }
    }
}
