package gag.nails.identity.internal;

import gag.nails.identity.Provider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface LoginMethodRepository extends JpaRepository<LoginMethodEntity, UUID> {

    Optional<LoginMethodEntity> findByProviderAndSubject(Provider provider, String subject);

    /** По времени привязки; при равном времени — по id (UUID v7 растёт со временем создания). */
    List<LoginMethodEntity> findAllByAccountIdOrderByLinkedAtAscIdAsc(UUID accountId);
}
