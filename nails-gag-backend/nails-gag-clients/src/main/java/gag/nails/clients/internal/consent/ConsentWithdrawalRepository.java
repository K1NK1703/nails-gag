package gag.nails.clients.internal.consent;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ConsentWithdrawalRepository extends JpaRepository<ConsentWithdrawalEntity, UUID> {

    /** Новые первыми; при равном времени — по id (UUID v7 растёт со временем создания). */
    List<ConsentWithdrawalEntity> findAllByClientIdOrderByWithdrawnAtDescIdDesc(UUID clientId);

    /** Без clearAutomatically: сущности клиентов, загруженные в той же транзакции, остаются управляемыми. */
    @Modifying(flushAutomatically = true)
    @Query("update ConsentWithdrawalEntity w set w.clientId = :to where w.clientId = :from")
    int reassign(UUID from, UUID to);
}
