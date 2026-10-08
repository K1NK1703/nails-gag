package gag.nails.clients.internal.client;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ClientRepository extends JpaRepository<ClientEntity, UUID> {

    Optional<ClientEntity> findByPhone(String phone);

    Optional<ClientEntity> findByAccountId(UUID accountId);

    /** Карточки без аккаунта с такой почтой без учёта регистра: кандидаты на привязку при регистрации. */
    @Query("select c from ClientEntity c where lower(c.email) = lower(:email) and c.accountId is null")
    List<ClientEntity> findAllUnregisteredByEmail(String email);

    /**
     * {@code pattern} — уже в нижнем регистре, с {@code %} по краям; {@code %}, {@code _} и {@code !}
     * внутри экранированы {@code !}. Обезличенные карточки не ищутся.
     */
    @Query("""
            select c from ClientEntity c
            where c.erasedAt is null
              and (lower(c.firstName) like :pattern escape '!'
                   or lower(c.lastName) like :pattern escape '!'
                   or c.phone like :pattern escape '!'
                   or c.contactPhone like :pattern escape '!')
            order by c.firstName, c.lastName, c.id
            """)
    List<ClientEntity> search(String pattern);
}
