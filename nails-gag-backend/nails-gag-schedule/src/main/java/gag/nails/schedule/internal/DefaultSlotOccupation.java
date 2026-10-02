package gag.nails.schedule.internal;

import gag.nails.schedule.SlotOccupation;
import gag.nails.schedule.SlotView;
import gag.nails.schedule.error.SlotNotFoundException;
import gag.nails.schedule.error.SlotOccupiedException;
import gag.nails.schedule.internal.error.SlotNotHeldByBookingException;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultSlotOccupation implements SlotOccupation {

    private final SlotRepository slots;
    private final EntityManager entityManager;

    DefaultSlotOccupation(SlotRepository slots, EntityManager entityManager) {
        this.slots = slots;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public SlotView get(UUID slotId) {
        return require(slotId).toView();
    }

    @Override
    public void occupy(UUID slotId, UUID bookingId) {
        if (slots.occupy(slotId, bookingId) == 0) {
            require(slotId);
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

    private SlotEntity require(UUID slotId) {
        return slots.findById(slotId).orElseThrow(() -> new SlotNotFoundException(slotId));
    }

    /**
     * Условное обновление идёт мимо контекста персистентности: копия слота, загруженная раньше в
     * этой же транзакции, иначе осталась бы со старыми {@code occupied}/{@code bookingId}.
     */
    private void refresh(UUID slotId) {
        var slot = entityManager.find(SlotEntity.class, slotId);
        if (slot != null) {
            entityManager.refresh(slot);
        }
    }
}
