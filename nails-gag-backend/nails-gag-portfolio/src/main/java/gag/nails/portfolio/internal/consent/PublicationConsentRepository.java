package gag.nails.portfolio.internal.consent;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Запросы идут мимо контекста персистентности: сущности согласий в нём не бывает. */
interface PublicationConsentRepository extends JpaRepository<PublicationConsentEntity, UUID> {

    /**
     * Отмечает согласие, если его ещё нет. {@code ON CONFLICT} вместо проверки в коде: повторное согласие
     * (событие приходит при каждой записи) не прерывает транзакцию нарушением ключа, время первого остаётся.
     */
    @Modifying
    @Query(
            value = "insert into portfolio.publication_consent (client_id, given_at) values (:clientId, :givenAt)"
                    + " on conflict (client_id) do nothing",
            nativeQuery = true)
    void insertIfAbsent(UUID clientId, Instant givenAt);

    /** Согласие удалённой при объединении карточки переходит к оставшейся, если у той его не было. */
    @Modifying
    @Query(value = """
                    insert into portfolio.publication_consent (client_id, given_at)
                    select :survivorClientId, given_at from portfolio.publication_consent
                    where client_id = :removedClientId
                    on conflict (client_id) do nothing""", nativeQuery = true)
    void copy(UUID removedClientId, UUID survivorClientId);

    @Modifying
    @Query("delete from PublicationConsentEntity c where c.clientId = :clientId")
    void deleteByClient(UUID clientId);
}
