package gag.nails.schedule;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.schedule.model.SlotView;
import java.time.LocalDate;
import java.util.List;

/** Чтение слотов. Периоды — даты в поясе салона, обе границы включительно. */
public interface ScheduleQueries {

    /**
     * Свободные слоты периода по времени начала. Открыто всем: слот не содержит личных данных.
     * Возвращает и прошедшие, и ещё не доступные для записи слоты: правила доступности (например,
     * отсечка перед началом) живут в модуле записей. Ограничение и постраничная выдача клиентских
     * запросов — задача HTTP-слоя.
     *
     * @throws ValidationException если {@code to} раньше {@code from}
     */
    List<SlotView> freeSlots(LocalDate from, LocalDate to);

    /**
     * Все слоты дня, включая занятые с идентификатором записи. Нужен уровень чтения раздела «Расписание».
     *
     * @throws AccessDeniedException если у актора нет чтения раздела «Расписание»
     */
    List<SlotView> slotsOfDay(LocalDate date, Actor actor);

    /**
     * Все слоты периода, включая занятые. Нужен уровень чтения раздела «Расписание».
     *
     * @throws ValidationException если {@code to} раньше {@code from}
     * @throws AccessDeniedException если у актора нет чтения раздела «Расписание»
     */
    List<SlotView> slotsOfPeriod(LocalDate from, LocalDate to, Actor actor);
}
