package gag.nails.booking.internal.reference;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ReferenceRepository extends JpaRepository<ReferenceEntity, UUID> {

    /** В порядке добавления. */
    List<ReferenceEntity> findAllByBookingIdOrderByCreatedAtAscFileIdAsc(UUID bookingId);

    long countByBookingId(UUID bookingId);

    boolean existsByBookingIdAndFileId(UUID bookingId, UUID fileId);

    /**
     * Записи с референсами, срок хранения которых истёк к {@code cutoff}: завершённые и отмеченные неявкой — по
     * началу визита (согласованное более раннее время или начало слота), отменённые — по моменту отмены. Строки
     * записей берутся под блокировку (SELECT … FOR UPDATE) в порядке идентификаторов, как у других операций над
     * несколькими записями; у строки, которую пришлось ждать, PostgreSQL перепроверяет условие по её новой версии.
     */
    @Query(value = """
                    select b.id from booking.booking b
                    where ((b.status in ('COMPLETED', 'NO_SHOW')
                            and coalesce(b.agreed_start, b.slot_starts_at) < :cutoff)
                           or (b.status = 'CANCELLED' and b.cancelled_at < :cutoff))
                      and exists (select 1 from booking.reference r where r.booking_id = b.id)
                    order by b.id
                    limit :limit
                    for update of b""", nativeQuery = true)
    List<UUID> lockBookingsWithExpiredReferences(Instant cutoff, int limit);
}
