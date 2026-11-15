package gag.nails.analytics.internal.maintenance;

import gag.nails.analytics.AnalyticsMaintenance;
import gag.nails.common.error.InternalException;
import gag.nails.common.time.SalonTimeZone;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Один оператор — два {@code DELETE} по ведущему столбцу первичных ключей ({@code day < граница}) в общем снимке, в
 * своей транзакции: соль дня и его отпечатки исчезают вместе, без промежуточного состояния. Блокируются только
 * удаляемые строки прошедших дней; приём пишет строки текущего дня (другие ключи) и уборки не ждёт, а она не ждёт его.
 *
 * <p>Пределы транзакции. Первым запросом ставятся {@code lock_timeout} ({@value #LOCK_TIMEOUT_SECONDS} с) и
 * {@code statement_timeout} ({@value #STATEMENT_TIMEOUT_SECONDS} с), только на эту транзакцию ({@code SET LOCAL}).
 * Без них чужая долгая блокировка таблицы (изменение схемы при выкладке, {@code VACUUM FULL}, ручная сессия) повесила
 * бы уборку, а запуск после старта синхронен с {@code ApplicationReadyEvent} и держал бы готовность приложения. Не
 * дождавшись, уборка падает с ошибкой базы и ничего не удаляет; следующий запуск попробует снова.
 *
 * <p>Полночь. Граница — день, который шёл {@link #PURGE_GRACE} назад: в первые пять минут нового дня вчерашний ещё не
 * трогается. Приём берёт день по часам перед своей транзакцией и пишет его меньше минуты (соединение из пула — до
 * 30 с по умолчанию Hikari, {@code statement_timeout} приёма — по 2 с на каждый из нескольких запросов транзакции; в
 * худшем случае около 30 с + 8 × 2 с — меньше минуты), поэтому к границе все события вчерашнего дня уже записаны:
 * уборка не стирает соль, под которой ещё считают, а приём не создаёт за вчера новую. Ночной запуск в 00:30 так ничего
 * не теряет; запуск после старта или ручной вызов в 00:00–00:05 оставляет вчерашний день ночному.
 *
 * <p>Объём. За день хранится не больше {@code max-visitors-per-day} отпечатков (100 000 по умолчанию, предел мягкий) —
 * под атакой около 15 МБ вместе с индексом; обычно убирается один прошедший день, после простоя — несколько. Удаление
 * по диапазону {@code day} занимает доли секунды, а удаляемые строки никто не пишет — поэтому без пакетов: они бы
 * ничего не разгрузили, но разнесли бы соль и её отпечатки по разным транзакциям. Мёртвые строки после удаления
 * убирает автоочистка PostgreSQL.
 *
 * <p>В лог пишется только число удалённых строк.
 */
@Service
class DefaultAnalyticsMaintenance implements AnalyticsMaintenance {

    /** Больше самой долгой записи события приёма: к границе события прошедшего дня уже записаны. */
    static final Duration PURGE_GRACE = Duration.ofMinutes(5);

    static final long LOCK_TIMEOUT_SECONDS = 5;
    static final long STATEMENT_TIMEOUT_SECONDS = 30;

    /** Пределы транзакции уборки, только на неё ({@code SET LOCAL}). */
    private static final String LIMITS = """
            select set_config('lock_timeout', :lockTimeout, true),
                   set_config('statement_timeout', :statementTimeout, true)""";

    /** Оба удаления — один оператор: один снимок и один обмен с базой. */
    private static final String PURGE = """
            with salts as (delete from analytics.day_salt where day < :before returning 1),
                 visitors as (delete from analytics.visitor where day < :before returning 1)
            select (select count(*) from visitors), (select count(*) from salts)""";

    private static final Logger log = LoggerFactory.getLogger(DefaultAnalyticsMaintenance.class);

    private final EntityManager entityManager;
    private final TransactionTemplate transaction;
    private final SalonTimeZone salonTimeZone;
    private final Clock clock;
    private final String lockTimeout;
    private final String statementTimeout;

    @Autowired
    DefaultAnalyticsMaintenance(
            EntityManager entityManager, TransactionTemplate transaction, SalonTimeZone salonTimeZone, Clock clock) {
        this(
                entityManager,
                transaction,
                salonTimeZone,
                clock,
                Duration.ofSeconds(LOCK_TIMEOUT_SECONDS),
                Duration.ofSeconds(STATEMENT_TIMEOUT_SECONDS));
    }

    /** Свои пределы ожидания — для тестов, которым не нужно ждать секунды. */
    DefaultAnalyticsMaintenance(
            EntityManager entityManager,
            TransactionTemplate transaction,
            SalonTimeZone salonTimeZone,
            Clock clock,
            Duration lockTimeout,
            Duration statementTimeout) {
        this.entityManager = entityManager;
        this.transaction = transaction;
        this.salonTimeZone = salonTimeZone;
        this.clock = clock;
        this.lockTimeout = lockTimeout.toMillis() + "ms";
        this.statementTimeout = statementTimeout.toMillis() + "ms";
    }

    @Override
    public int purgeExpired() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new InternalException("analytics purge must run outside a transaction");
        }
        var before = salonTimeZone.dayOf(clock.instant().minus(PURGE_GRACE));
        var removed = Objects.requireNonNull(transaction.execute(status -> purge(before)));
        if (removed.visitors() > 0 || removed.salts() > 0) {
            log.info(
                    "analytics purge removed {} visitor fingerprints and {} day salts",
                    removed.visitors(),
                    removed.salts());
        }
        return removed.visitors();
    }

    private Removed purge(LocalDate before) {
        entityManager
                .createNativeQuery(LIMITS)
                .setParameter("lockTimeout", lockTimeout)
                .setParameter("statementTimeout", statementTimeout)
                .getSingleResult();
        var row = (Object[]) entityManager
                .createNativeQuery(PURGE)
                .setParameter("before", before)
                .getSingleResult();
        return new Removed(((Number) row[0]).intValue(), ((Number) row[1]).intValue());
    }

    private record Removed(int visitors, int salts) {}
}
