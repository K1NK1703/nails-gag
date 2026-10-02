package gag.nails.schedule.internal;

import gag.nails.common.error.InternalException;
import gag.nails.common.persistence.ConstraintViolations;
import gag.nails.schedule.SlotOccupation;
import gag.nails.schedule.SlotView;
import gag.nails.schedule.error.SlotNotFoundException;
import gag.nails.schedule.error.SlotOccupiedException;
import gag.nails.schedule.internal.error.BookingHoldsAnotherSlotException;
import gag.nails.schedule.internal.error.SlotNotHeldByBookingException;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultSlotOccupation implements SlotOccupation {

    private static final String ONE_SLOT_PER_BOOKING_INDEX = "slot_booking_id_key";

    private final SlotRepository slots;
    private final EntityManager entityManager;

    DefaultSlotOccupation(SlotRepository slots, EntityManager entityManager) {
        this.slots = slots;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public SlotView get(UUID slotId) {
        return slots.findById(slotId)
                .orElseThrow(() -> new SlotNotFoundException(slotId))
                .toView();
    }

    @Override
    public void occupy(UUID slotId, UUID bookingId) {
        int updated;
        try {
            updated = slots.occupy(slotId, bookingId);
        } catch (DataIntegrityViolationException e) {
            throw ConstraintViolations.constraintName(e)
                    .filter(ONE_SLOT_PER_BOOKING_INDEX::equals)
                    .<InternalException>map(name -> new BookingHoldsAnotherSlotException(bookingId, e))
                    .orElseGet(() -> new InternalException("occupy violated constraint", e));
        }
        if (updated == 0) {
            // Проверка по базе, не по контексту персистентности: закэшированная копия могла устареть.
            if (!slots.existsById(slotId)) {
                throw new SlotNotFoundException(slotId);
            }
            throw new SlotOccupiedException(slotId);
        }
        refresh(slotId);
    }

    @Override
    public void release(UUID slotId, UUID bookingId) {
        if (slots.release(slotId, bookingId) == 0) {
            throw new SlotNotHeldByBookingException(slotId, bookingId);
        }
        refresh(slotId);
    }

    /**
     * Условное обновление идёт мимо контекста персистентности: копия слота, загруженная раньше в
     * этой же транзакции, иначе осталась бы со старыми {@code occupied}/{@code bookingId}. Если слот
     * не закэширован, {@code find} и {@code refresh} стоят два SELECT — принято, на
     * {@code clearAutomatically} не менять: он выбросил бы из контекста все остальные сущности.
     */
    private void refresh(UUID slotId) {
        var slot = entityManager.find(SlotEntity.class, slotId);
        if (slot != null) {
            entityManager.refresh(slot);
        }
    }
}
