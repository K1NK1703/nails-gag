package gag.nails.clients.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ClientRepository extends JpaRepository<ClientEntity, UUID> {

    Optional<ClientEntity> findByPhone(String phone);

    Optional<ClientEntity> findByAccountId(UUID accountId);

    /** {@code pattern} — уже в нижнем регистре и с {@code %} по краям. */
    @Query("""
            select c from ClientEntity c
            where lower(c.firstName) like :pattern
               or lower(c.lastName) like :pattern
               or c.phone like :pattern
            order by c.firstName, c.lastName
            """)
    List<ClientEntity> search(String pattern);
}
