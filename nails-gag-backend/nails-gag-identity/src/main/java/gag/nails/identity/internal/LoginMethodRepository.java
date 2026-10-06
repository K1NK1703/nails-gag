package gag.nails.identity.internal;

import gag.nails.identity.Provider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface LoginMethodRepository extends JpaRepository<LoginMethodEntity, UUID> {

    Optional<LoginMethodEntity> findByProviderAndSubject(Provider provider, String subject);

    /** Самый ранний способ входа с таким подтверждённым телефоном: по нему при входе узнаётся аккаунт. */
    Optional<LoginMethodEntity> findFirstByPhoneOrderByLinkedAtAscIdAsc(String phone);

    /** Самый ранний способ входа с такой подтверждённой почтой. */
    Optional<LoginMethodEntity> findFirstByEmailOrderByLinkedAtAscIdAsc(String email);

    /** По времени привязки; при равном времени — по id (UUID v7 растёт со временем создания). */
    List<LoginMethodEntity> findAllByAccountIdOrderByLinkedAtAscIdAsc(UUID accountId);

    /** Способ входа, использованный последним: его имя и аватарка показываются в профиле. */
    Optional<LoginMethodEntity> findFirstByAccountIdOrderByLastUsedAtDescIdDesc(UUID accountId);

    void deleteAllByAccountId(UUID accountId);
}
