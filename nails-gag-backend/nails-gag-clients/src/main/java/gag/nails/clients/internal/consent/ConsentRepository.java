package gag.nails.clients.internal.consent;

import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentWithdrawalView;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ConsentRepository extends JpaRepository<ConsentEntity, UUID> {

    /** Новые первыми; при равном времени — по id (UUID v7 растёт со временем создания). */
    List<ConsentEntity> findAllByClientIdOrderByGivenAtDescIdDesc(UUID clientId);

    boolean existsByClientIdAndKindAndDocumentVersionAndWithdrawnAtIsNull(
            UUID clientId, ConsentKind kind, String documentVersion);

    boolean existsByClientIdAndKindAndWithdrawnAtIsNull(UUID clientId, ConsentKind kind);

    /**
     * Под блокировкой строк: одновременный второй отзыв ждёт первый, затем строки ему уже не подходят — он ничего
     * не меняет и события не публикует.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<ConsentEntity> findAllByClientIdAndKindAndWithdrawnAtIsNull(UUID clientId, ConsentKind kind);

    /**
     * Отзывы, новые первыми: один отзыв помечает все действовавшие строки вида одним временем, поэтому строки
     * сводятся в один отзыв.
     */
    @Query("""
            select distinct new gag.nails.clients.model.ConsentWithdrawalView(c.kind, c.withdrawnAt, c.withdrawnBy)
            from ConsentEntity c
            where c.clientId = :clientId and c.withdrawnAt is not null
            order by c.withdrawnAt desc, c.kind""")
    List<ConsentWithdrawalView> findWithdrawals(UUID clientId);

    /** Без clearAutomatically: сущности клиентов, загруженные в той же транзакции, остаются управляемыми. */
    @Modifying(flushAutomatically = true)
    @Query("update ConsentEntity c set c.clientId = :to where c.clientId = :from")
    int reassign(UUID from, UUID to);
}
