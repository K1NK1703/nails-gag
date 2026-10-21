package gag.nails.clients.internal.client;

import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;

/**
 * Блокировка — отдельным запросом, а не {@code @Lock} на выборке сущности: Hibernate вернул бы уже загруженную в этой
 * транзакции сущность со старым состоянием. Поэтому сначала строка блокируется, затем сущность перечитывается.
 */
class ClientLockingImpl implements ClientLocking {

    /** Как {@code PESSIMISTIC_WRITE} у Hibernate для PostgreSQL. */
    private static final String LOCK_ROW = "select id from clients.client where id = :id for no key update";

    private final EntityManager entityManager;

    ClientLockingImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<ClientEntity> findLockedById(UUID id) {
        entityManager.flush();
        var locked =
                entityManager.createNativeQuery(LOCK_ROW).setParameter("id", id).getResultList();
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        var client = entityManager.find(ClientEntity.class, id);
        entityManager.refresh(client);
        return Optional.of(client);
    }
}
