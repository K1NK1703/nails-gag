package gag.nails.booking.internal.booking;

import gag.nails.booking.model.BookingStatus;
import gag.nails.booking.model.EarlierTimeStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface BookingRepository extends JpaRepository<BookingEntity, UUID> {

    /**
     * Запись под блокировкой строки (SELECT … FOR UPDATE): параллельное изменение той же записи дождётся конца
     * транзакции и увидит её новое состояние.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BookingEntity b where b.id = :bookingId")
    Optional<BookingEntity> findLockedById(UUID bookingId);

    long countByClientIdAndStatusIn(UUID clientId, Collection<BookingStatus> statuses);

    /**
     * Все записи клиента под блокировкой строк, в постоянном порядке: две транзакции, берущие один и
     * тот же набор, не блокируют друг друга накрест.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BookingEntity b where b.clientId = :clientId order by b.id")
    List<BookingEntity> findAllLockedByClientId(UUID clientId);

    /** Ближайшие первыми. */
    List<BookingEntity> findAllByClientIdAndStatusInOrderBySlotStartsAtAscIdAsc(
            UUID clientId, Collection<BookingStatus> statuses);

    /** Последние первыми. */
    List<BookingEntity> findAllByClientIdAndStatusInOrderBySlotStartsAtDescIdDesc(
            UUID clientId, Collection<BookingStatus> statuses);

    /** Записи со слотом, начинающимся в полуинтервале {@code [from, to)}, по времени начала. */
    @Query("select b from BookingEntity b where b.slotStartsAt >= :from and b.slotStartsAt < :to"
            + " order by b.slotStartsAt, b.id")
    List<BookingEntity> startingBetween(Instant from, Instant to);

    /** Активные записи, слот которых уже начался: ждут отметки мастера. */
    @Query("select b from BookingEntity b where b.status in :statuses and b.slotStartsAt <= :now"
            + " order by b.slotStartsAt, b.id")
    List<BookingEntity> startedBy(Collection<BookingStatus> statuses, Instant now);

    List<BookingEntity> findAllByStatusOrderByPrepaymentDeadlineAscIdAsc(BookingStatus status);

    @Query("select b.id from BookingEntity b where b.status = :status and b.prepaymentDeadline < :now")
    List<UUID> idsWithPrepaymentDeadlineBefore(BookingStatus status, Instant now);

    @Query("select b.id from BookingEntity b where b.offerStatus = :offerStatus and b.slotStartsAt <= :now")
    List<UUID> idsWithOfferAndSlotStartedBy(EarlierTimeStatus offerStatus, Instant now);
}
