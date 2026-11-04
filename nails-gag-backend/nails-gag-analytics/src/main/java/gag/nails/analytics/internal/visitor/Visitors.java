package gag.nails.analytics.internal.visitor;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Уникальные посетители дня по суточному отпечатку. Только внутри транзакции приёма ({@code MANDATORY}). */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class Visitors {

    private static final String INSERT =
            "insert into analytics.visitor (day, hash) values (:day, :hash) on conflict (day, hash) do nothing";

    private final EntityManager entityManager;
    private final DaySalts salts;

    Visitors(EntityManager entityManager, DaySalts salts) {
        this.entityManager = entityManager;
        this.salts = salts;
    }

    /**
     * Отмечает посетителя за день.
     *
     * @return {@code true}, если за этот день он впервые: тот же IP и {@code User-Agent} в тот же день дают тот же
     *     отпечаток, а в другой день — другой (у дня своя соль)
     */
    public boolean firstVisitOfDay(LocalDate day, Visitor visitor) {
        var hash = Fingerprints.of(salts.of(day), visitor);
        return entityManager
                        .createNativeQuery(INSERT)
                        .setParameter("day", day)
                        .setParameter("hash", hash)
                        .executeUpdate()
                == 1;
    }
}
