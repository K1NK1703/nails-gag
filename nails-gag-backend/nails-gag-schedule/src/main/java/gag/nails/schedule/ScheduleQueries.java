package gag.nails.schedule;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import java.time.LocalDate;
import java.util.List;

/** Чтение слотов. Периоды — даты в поясе салона, обе границы включительно. */
public interface ScheduleQueries {

    /**
     * Свободные слоты периода по времени начала. Открыто всем: слот не содержит личных данных.
     *
     * @throws ValidationException если {@code to} раньше {@code from}
     */
    List<SlotView> freeSlots(LocalDate from, LocalDate to);

    /**
     * Все слоты дня, включая занятые с идентификатором записи. Только мастер.
     *
     * @throws AccessDeniedException если действует не мастер
     */
    List<SlotView> slotsOfDay(LocalDate date, Actor actor);

    /**
     * Все слоты периода, включая занятые. Только мастер.
     *
     * @throws ValidationException если {@code to} раньше {@code from}
     * @throws AccessDeniedException если действует не мастер
     */
    List<SlotView> slotsOfPeriod(LocalDate from, LocalDate to, Actor actor);
}
