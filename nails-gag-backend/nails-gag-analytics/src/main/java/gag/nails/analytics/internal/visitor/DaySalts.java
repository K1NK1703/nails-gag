package gag.nails.analytics.internal.visitor;

import gag.nails.common.error.InternalException;
import jakarta.persistence.EntityManager;
import java.security.SecureRandom;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Соль дня: 32 случайных байта, одна на день, создаётся первым событием дня. Только внутри транзакции приёма
 * ({@code MANDATORY}).
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class DaySalts {

    private static final String SELECT = "select salt from analytics.day_salt where day = :day";

    /**
     * Два одновременных первых события дня: вставка второго ждёт фиксации первого на первичном ключе и ничего не
     * вставляет, а следующее чтение ({@code READ COMMITTED} — новый снимок на каждый запрос) видит соль первого. Так
     * у дня одна соль и один отпечаток у одного посетителя.
     */
    private static final String INSERT =
            "insert into analytics.day_salt (day, salt) values (:day, :salt) on conflict (day) do nothing";

    private final EntityManager entityManager;
    private final SecureRandom random = new SecureRandom();

    DaySalts(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Соль дня; если её ещё нет — создаёт. */
    byte[] of(LocalDate day) {
        var salt = find(day);
        if (salt != null) {
            return salt;
        }
        var fresh = new byte[Fingerprints.SIZE];
        random.nextBytes(fresh);
        entityManager
                .createNativeQuery(INSERT)
                .setParameter("day", day)
                .setParameter("salt", fresh)
                .executeUpdate();
        var stored = find(day);
        if (stored == null) {
            throw new InternalException("salt of " + day + " is missing right after insert");
        }
        return stored;
    }

    private byte @Nullable [] find(LocalDate day) {
        var rows = entityManager
                .createNativeQuery(SELECT, byte[].class)
                .setParameter("day", day)
                .getResultList();
        return rows.isEmpty() ? null : (byte[]) rows.getFirst();
    }
}
