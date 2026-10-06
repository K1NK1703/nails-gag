package gag.nails.identity.internal;

import gag.nails.identity.Provider;
import gag.nails.identity.internal.provider.ProviderProfile;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * Сериализация входов одного человека: два одновременных первых входа с одним {@code subject},
 * телефоном или почтой иначе завели бы два аккаунта. Транзакционные advisory-блокировки PostgreSQL по
 * ключам {@code provider:subject}, {@code phone:<E.164>}, {@code email:<почта>}, в лексикографическом
 * порядке ключей, чтобы два входа с пересекающимися ключами не ждали друг друга крест-накрест.
 * Отпускаются с концом транзакции; внутри одной транзакции повторный захват не блокирует.
 */
@Component
class LoginLocks {

    @PersistenceContext
    private EntityManager entityManager;

    void acquire(Provider provider, ProviderProfile profile) {
        var keys = new TreeSet<String>();
        keys.add(provider.name() + ":" + profile.subject());
        if (profile.phone() != null) {
            keys.add("phone:" + profile.phone());
        }
        if (profile.email() != null) {
            keys.add("email:" + profile.email());
        }
        for (var key : keys) {
            entityManager
                    .createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                    .setParameter("key", key)
                    .getSingleResult();
        }
    }
}
