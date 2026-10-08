package gag.nails.schedule;

import gag.nails.schedule.error.SlotNotFoundException;
import gag.nails.schedule.error.SlotOccupiedException;
import gag.nails.schedule.model.SlotView;
import java.util.UUID;

/**
 * Для модуля записей: занять и освободить слот, прочитать слот. Без {@code Actor}: права проверяет
 * модуль записей; наружу по HTTP не отдавать. Вызывать в транзакции записи — ошибка откатит и слот.
 *
 * <p>Одна запись держит не больше одного слота (уникальный индекс по {@code booking_id}): при
 * переносе сначала {@link #release}, потом {@link #occupy}.
 */
public interface SlotOccupation {

    /**
     * Слот по идентификатору, включая занятость и запись, которая его держит.
     *
     * @throws SlotNotFoundException если слота нет
     */
    SlotView get(UUID slotId);

    /**
     * Занимает свободный слот записью. Условное обновление: из двух параллельных попыток проходит
     * ровно одна, вторая получает {@link SlotOccupiedException}.
     *
     * @throws SlotOccupiedException если слот уже занят
     * @throws SlotNotFoundException если слота нет
     */
    void occupy(UUID slotId, UUID bookingId);

    /**
     * Освобождает слот, занятый именно этой записью. Слот, занятый другой записью или свободный, —
     * ошибка в коде вызывающего модуля (внутреннее исключение).
     */
    void release(UUID slotId, UUID bookingId);
}
