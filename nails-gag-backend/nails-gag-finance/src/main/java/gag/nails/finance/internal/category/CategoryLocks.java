package gag.nails.finance.internal.category;

import gag.nails.finance.model.EntryKind;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Транзакционные advisory-блокировки PostgreSQL модуля; отпускаются с концом транзакции, повторный захват в той же
 * транзакции не блокирует. Нужны там, где строки для {@code SELECT … FOR UPDATE} ещё нет: новая категория — новая
 * строка.
 *
 * <p>Порядок блокировок модуля: список категорий вида → строки записей → строки категорий. Изменения категорий берут
 * список вида, затем строки категорий; изменения записей — строку записи, затем строку её категории (разделяемо).
 * Модуль не ждёт блокировок других модулей, поэтому встречного порядка нет. Ключи начинаются с {@code finance.}:
 * ожидание видно в {@code pg_stat_activity} по тексту запроса.
 *
 * <p>Только внутри транзакции ({@code MANDATORY}): вне её блокировка отпустилась бы сразу и ничего не защищала бы.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class CategoryLocks {

    private static final String CATEGORIES =
            "select pg_advisory_xact_lock(hashtextextended('finance.categories:' || :kind, 0))";

    private final EntityManager entityManager;

    CategoryLocks(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Список категорий вида: добавления, переименования и перестановки вида идут по одной. Иначе два одновременных
     * добавления получили бы одну позицию ({@code max + 1}) или оба прошли бы проверку названия, а перестановка не
     * увидела бы добавленную в это время категорию.
     */
    void categories(EntryKind kind) {
        entityManager
                .createNativeQuery(CATEGORIES)
                .setParameter("kind", kind.name())
                .getSingleResult();
    }
}
