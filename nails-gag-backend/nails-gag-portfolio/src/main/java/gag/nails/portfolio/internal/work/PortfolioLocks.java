package gag.nails.portfolio.internal.work;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Транзакционные advisory-блокировки PostgreSQL модуля; отпускаются с концом транзакции, повторный захват в той же
 * транзакции не блокирует. Нужны там, где строки для {@code SELECT … FOR UPDATE} ещё нет: новая работа — новая строка.
 *
 * <p>Порядок блокировок модуля: порядок работ → клиент → строки работ (по id) → строки файлов. Синхронные подписчики
 * событий клиентов приходят с уже взятой карточкой клиента и берут блокировку клиента и строки работ после неё; сам
 * модуль карточек не блокирует, поэтому встречного порядка нет. Ключи начинаются с {@code portfolio.}: ожидание
 * видно в {@code pg_stat_activity} по тексту запроса.
 *
 * <p>Только внутри транзакции ({@code MANDATORY}): вне её блокировка отпустилась бы сразу и ничего не защищала бы.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class PortfolioLocks {

    private static final String ORDER = "select pg_advisory_xact_lock(hashtextextended('portfolio.work-order', 0))";

    private static final String CLIENT =
            "select pg_advisory_xact_lock(hashtextextended('portfolio.client:' || cast(:clientId as text), 0))";

    private final EntityManager entityManager;

    PortfolioLocks(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Порядок работ: добавления и перестановки идут по одной. Иначе два одновременных добавления получили бы одну
     * позицию ({@code max + 1}), а перестановка не увидела бы добавленную в это время работу.
     */
    void order() {
        entityManager.createNativeQuery(ORDER).getSingleResult();
    }

    /**
     * Работы клиента: добавление его работы и подписчики объединения и обезличивания его карточки идут по одной.
     * Иначе работа, добавленная одновременно с обезличиванием, пережила бы его, а добавленная одновременно с
     * объединением осталась бы у удалённой карточки.
     */
    void client(UUID clientId) {
        entityManager
                .createNativeQuery(CLIENT)
                .setParameter("clientId", clientId)
                .getSingleResult();
    }
}
