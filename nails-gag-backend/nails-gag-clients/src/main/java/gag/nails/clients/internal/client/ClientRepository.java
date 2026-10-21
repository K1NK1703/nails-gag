package gag.nails.clients.internal.client;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface ClientRepository extends JpaRepository<ClientEntity, UUID> {

    /**
     * Условие поиска, общее для списка, страницы и её подсчёта. {@code pattern} — уже в нижнем регистре, с
     * {@code %} по краям; {@code %}, {@code _} и {@code !} внутри экранированы {@code !}. Обезличенные карточки
     * не ищутся.
     */
    String SEARCH = """
            from ClientEntity c
            where c.erasedAt is null
              and (lower(c.firstName) like :pattern escape '!'
                   or lower(c.lastName) like :pattern escape '!'
                   or c.phone like :pattern escape '!'
                   or c.contactPhone like :pattern escape '!')
            """;

    /** Порядок поиска: по алфавиту, при совпадении имён — по id, чтобы страницы не перекрывались. */
    String SEARCH_ORDER = "order by c.firstName, c.lastName, c.id";

    /**
     * Карточка под блокировкой строки (SELECT … FOR UPDATE) до конца транзакции. Ею сериализуются запись и
     * отзыв согласия, объединение и обезличивание: кто пришёл вторым, увидит результат первого (удалённую при
     * объединении карточку — как отсутствующую). Порядок блокировок: карточка, затем строки согласий; две
     * карточки — в порядке id.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ClientEntity c where c.id = :id")
    Optional<ClientEntity> findLockedById(UUID id);

    Optional<ClientEntity> findByPhone(String phone);

    Optional<ClientEntity> findByAccountId(UUID accountId);

    /**
     * Карточки без аккаунта с такой почтой без учёта регистра: кандидаты на привязку при регистрации и
     * при сопоставлении входа ({@code ClientQueries.byEmail}).
     */
    @Query("select c from ClientEntity c where lower(c.email) = lower(:email) and c.accountId is null")
    List<ClientEntity> findAllUnregisteredByEmail(String email);

    /** Условие — {@link #SEARCH}. */
    @Query("select c " + SEARCH + SEARCH_ORDER)
    List<ClientEntity> search(String pattern);

    /** То же, что {@link #search(String)}, постранично; {@code pageable} без сортировки — порядок задан здесь. */
    @Query(value = "select c " + SEARCH + SEARCH_ORDER, countQuery = "select count(c) " + SEARCH)
    Page<ClientEntity> search(String pattern, Pageable pageable);
}
