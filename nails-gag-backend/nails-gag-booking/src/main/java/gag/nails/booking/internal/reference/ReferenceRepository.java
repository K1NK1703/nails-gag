package gag.nails.booking.internal.reference;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Срок хранения в запросах уборки: у отменённой записи он отсчитывается от отмены, у любой другой (в том числе не
 * отмеченной мастером) — от начала визита, {@code coalesce(agreed_start, slot_starts_at)}, как
 * {@code BookingView.visitStart}.
 */
interface ReferenceRepository extends JpaRepository<ReferenceEntity, UUID> {

    /** В порядке добавления. */
    List<ReferenceEntity> findAllByBookingIdOrderByCreatedAtAscFileIdAsc(UUID bookingId);

    /** Референсы нескольких записей одним запросом: по записям, внутри записи — в порядке добавления. */
    List<ReferenceEntity> findAllByBookingIdInOrderByBookingIdAscCreatedAtAscFileIdAsc(Collection<UUID> bookingIds);

    long countByBookingId(UUID bookingId);

    boolean existsByBookingIdAndFileId(UUID bookingId, UUID fileId);

    /**
     * Очередная страница записей, у которых есть референсы с истёкшим к {@code cutoff} сроком, — идентификаторы
     * больше {@code after} по возрастанию, без блокировки. Ищет от референсов, чтобы не обходить историю записей:
     * записей с референсами немного, а записей за годы — много.
     */
    @Query(value = """
                    select b.id from booking.booking b
                    where b.id = any (array(select distinct r.booking_id from booking.reference r))
                      and b.id > :after
                      and ((b.status = 'CANCELLED' and b.cancelled_at < :cutoff)
                           or (b.status <> 'CANCELLED' and coalesce(b.agreed_start, b.slot_starts_at) < :cutoff))
                    order by b.id
                    limit :limit""", nativeQuery = true)
    List<UUID> findBookingsWithExpiredReferences(Instant cutoff, UUID after, int limit);

    /**
     * Из {@code bookingIds} — записи, срок референсов которых истёк к {@code cutoff}, под блокировкой строк
     * (SELECT … FOR NO KEY UPDATE, как у остальных изменений записи) в порядке идентификаторов. Условие проверяется
     * заново: между выборкой страницы и блокировкой запись могли изменить, а у строки, которую пришлось ждать,
     * PostgreSQL проверяет его по новой версии.
     */
    @Query(value = """
                    select b.id from booking.booking b
                    where b.id in (:bookingIds)
                      and ((b.status = 'CANCELLED' and b.cancelled_at < :cutoff)
                           or (b.status <> 'CANCELLED' and coalesce(b.agreed_start, b.slot_starts_at) < :cutoff))
                    order by b.id
                    for no key update of b""", nativeQuery = true)
    List<UUID> lockExpired(Collection<UUID> bookingIds, Instant cutoff);
}
