package gag.nails.notifications.internal.reminder;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Транзакционная advisory-блокировка PostgreSQL на напоминания одной записи; отпускается с концом транзакции,
 * повторный захват в той же транзакции не блокирует. Строки записи в схеме уведомлений нет (запись — в чужой схеме,
 * её напоминаний может ещё не быть), поэтому {@code SELECT … FOR UPDATE} не подходит. Ключ —
 * {@code hashtextextended('notifications.reminders:<booking_id>')}: совпадение хеша двух записей лишь заставит их
 * подождать друг друга. Ключ начинается с {@code notifications.}, и он виден в тексте запроса — ожидание этой
 * блокировки находится в {@code pg_stat_activity} по шаблону {@code %notifications.%}.
 *
 * <p>Только внутри транзакции ({@code MANDATORY}): вне её блокировка отпустилась бы сразу и ничего не защищала бы.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class ReminderLocks {

    private static final String BOOKING = "select pg_advisory_xact_lock("
            + "hashtextextended('notifications.reminders:' || cast(:bookingId as text), 0))";

    private final JdbcClient jdbc;

    ReminderLocks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Напоминания записи: перепланирование и отмена одной записи слушателями событий идут по одной (строку настроек
     * на чтение они держат одновременно).
     */
    void booking(UUID bookingId) {
        jdbc.sql(BOOKING).param("bookingId", bookingId).query().singleRow();
    }
}
