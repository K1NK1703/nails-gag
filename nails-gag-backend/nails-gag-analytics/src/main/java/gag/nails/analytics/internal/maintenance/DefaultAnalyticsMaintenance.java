package gag.nails.analytics.internal.maintenance;

import gag.nails.analytics.AnalyticsMaintenance;
import gag.nails.common.error.InternalException;
import gag.nails.common.time.SalonTimeZone;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Два {@code DELETE} по ведущему столбцу первичных ключей ({@code day < сегодня}) в одной своей транзакции: соль дня и
 * его отпечатки исчезают вместе, без промежуточного состояния. Блокируются только удаляемые строки прошедших дней;
 * приём пишет строки сегодняшнего дня (другие ключи) и уборки не ждёт, а она не ждёт его.
 *
 * <p>Полночь. Приём берёт день по часам перед своей транзакцией, уборка — перед своей. Если уборка запущена в первые
 * секунды нового дня (запуск после старта или ручной вызов; ночной в 00:30 от этого далёк), опоздавшее событие
 * вчерашнего дня может записаться после удаления: тогда за вчера остаются соль и отпечаток до следующей уборки, а если
 * соль уже была удалена, приём создаёт новую — посетитель, уже посчитанный вчера, может посчитаться за вчера ещё раз.
 * Такое событие может и подождать фиксации уборки на удаляемой строке и, не дождавшись ({@code lock_timeout} приёма),
 * потеряться с записью в лог. Всё это ограничено событиями тех секунд: лишний или потерянный посетитель вчерашнего
 * дня, остальные счётчики верны, а оставшиеся строки уберёт следующая уборка. Связывать приём и уборку блокировкой
 * ради этого не стоит — она задерживала бы каждое событие.
 *
 * <p>Объём. За день хранится не больше {@code max-visitors-per-day} отпечатков (100 000 по умолчанию, предел мягкий) —
 * порядка десяти мегабайт вместе с индексом; обычно убирается один прошедший день, после простоя — несколько. Один
 * {@code DELETE} по диапазону {@code day} занимает доли секунды, а удаляемые строки никто, кроме опоздавших событий из
 * абзаца выше, не пишет — поэтому без пакетов: они бы ничего не разгрузили, но разнесли бы соль и её отпечатки по
 * разным транзакциям. Мёртвые строки после удаления убирает автоочистка PostgreSQL.
 *
 * <p>В лог пишется только число удалённых строк.
 */
@Service
class DefaultAnalyticsMaintenance implements AnalyticsMaintenance {

    private static final String DELETE_VISITORS = "delete from analytics.visitor where day < :today";
    private static final String DELETE_SALTS = "delete from analytics.day_salt where day < :today";

    private static final Logger log = LoggerFactory.getLogger(DefaultAnalyticsMaintenance.class);

    private final EntityManager entityManager;
    private final TransactionTemplate transaction;
    private final SalonTimeZone salonTimeZone;
    private final Clock clock;

    DefaultAnalyticsMaintenance(
            EntityManager entityManager, TransactionTemplate transaction, SalonTimeZone salonTimeZone, Clock clock) {
        this.entityManager = entityManager;
        this.transaction = transaction;
        this.salonTimeZone = salonTimeZone;
        this.clock = clock;
    }

    @Override
    public int purgeExpired() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new InternalException("analytics purge must run outside a transaction");
        }
        var today = salonTimeZone.today(clock);
        var removed = Objects.requireNonNull(transaction.execute(
                status -> new Removed(delete(DELETE_VISITORS, today), delete(DELETE_SALTS, today))));
        if (removed.visitors() > 0 || removed.salts() > 0) {
            log.info(
                    "analytics purge removed {} visitor fingerprints and {} day salts",
                    removed.visitors(),
                    removed.salts());
        }
        return removed.visitors();
    }

    private int delete(String sql, LocalDate today) {
        return entityManager.createNativeQuery(sql).setParameter("today", today).executeUpdate();
    }

    private record Removed(int visitors, int salts) {}
}
