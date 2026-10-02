package gag.nails.schedule.internal;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface SlotRepository extends JpaRepository<SlotEntity, UUID> {

    List<SlotEntity> findAllByDayOrderByStartsAt(LocalDate day);

    List<SlotEntity> findAllByDayBetweenOrderByStartsAt(LocalDate from, LocalDate to);

    List<SlotEntity> findAllByDayBetweenAndOccupiedFalseOrderByStartsAt(LocalDate from, LocalDate to);

    /** Даты из набора, в которых уже есть хотя бы один слот. Набор не пуст. */
    @Query("select distinct s.day from SlotEntity s where s.day in :days")
    List<LocalDate> openDays(Collection<LocalDate> days);

    /** Даты периода (обе границы включительно), в которых уже есть хотя бы один слот. */
    @Query("select distinct s.day from SlotEntity s where s.day between :from and :to")
    List<LocalDate> openDaysBetween(LocalDate from, LocalDate to);

    /**
     * Условное занятие: обновляет ровно одну строку, если слот ещё свободен. Без clearAutomatically:
     * копию слота в контексте персистентности обновляет вызывающий код (см. DefaultSlotOccupation).
     */
    @Modifying(flushAutomatically = true)
    @Query("update SlotEntity s set s.occupied = true, s.bookingId = :bookingId"
            + " where s.id = :slotId and s.occupied = false")
    int occupy(UUID slotId, UUID bookingId);

    /** Освобождает слот только если он занят именно этой записью. */
    @Modifying(flushAutomatically = true)
    @Query("update SlotEntity s set s.occupied = false, s.bookingId = null"
            + " where s.id = :slotId and s.bookingId = :bookingId")
    int release(UUID slotId, UUID bookingId);
}
