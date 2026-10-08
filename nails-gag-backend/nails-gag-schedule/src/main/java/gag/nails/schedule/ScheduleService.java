package gag.nails.schedule;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.schedule.command.ApplyRotation;
import gag.nails.schedule.error.DayTemplateNotFoundException;
import gag.nails.schedule.error.RotationNotConfiguredException;
import gag.nails.schedule.error.SlotNotFoundException;
import gag.nails.schedule.error.SlotOccupiedException;
import gag.nails.schedule.error.SlotOverlapException;
import gag.nails.schedule.error.TemplateOverlapException;
import gag.nails.schedule.model.RotationPreview;
import gag.nails.schedule.model.SlotView;
import gag.nails.schedule.model.TemplateApplication;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Операции с графиком: слоты и применение шаблонов. Изменения требуют уровня изменения раздела
 * «Расписание», предпросмотр ротации — уровня чтения. Существующий слот открыт для записи,
 * закрыть слот — удалить его. Клиентские операции слотов не касаются: запись занимает слот через
 * {@link SlotOccupation}.
 */
public interface ScheduleService {

    /**
     * Открывает слот с длительностью из текущей настройки. Дата и время — в поясе салона. Слот в
     * прошлом допустим: так мастер вносит визит, прошедший без записи.
     *
     * @throws SlotOverlapException если интервал пересекается с существующим слотом
     * @throws AccessDeniedException если у актора нет изменения раздела «Расписание»
     */
    SlotView addSlot(LocalDate date, LocalTime startTime, Actor actor);

    /**
     * Сдвигает свободный слот на другое время того же дня; длительность сохраняется.
     *
     * @throws SlotNotFoundException если слота нет
     * @throws SlotOccupiedException если слот занят записью: сначала отмена или перенос записи
     * @throws SlotOverlapException если новый интервал пересекается с другим слотом
     * @throws AccessDeniedException если у актора нет изменения раздела «Расписание»
     */
    SlotView moveSlot(UUID slotId, LocalTime newStartTime, Actor actor);

    /**
     * Закрывает свободный слот.
     *
     * @throws SlotNotFoundException если слота нет
     * @throws SlotOccupiedException если слот занят записью
     * @throws AccessDeniedException если у актора нет изменения раздела «Расписание»
     */
    void deleteSlot(UUID slotId, Actor actor);

    /**
     * Открывает дни по шаблону дня: для каждой даты создаёт слоты по его временам с текущей
     * длительностью. Дни, где уже есть хотя бы один слот, пропускает и возвращает в
     * {@link TemplateApplication#skipped()}. Повторы дат схлопываются.
     *
     * @throws DayTemplateNotFoundException если шаблона нет
     * @throws TemplateOverlapException если времена шаблона пересекаются при текущей длительности
     * @throws SlotOverlapException если слот пересёкся со слотом соседнего дня (слот через полночь)
     * @throws ValidationException если список дат пуст
     * @throws AccessDeniedException если у актора нет изменения раздела «Расписание»
     */
    TemplateApplication applyDayTemplate(UUID templateId, List<LocalDate> dates, Actor actor);

    /** Открывает один день по шаблону: то же, что {@link #applyDayTemplate} с одной датой. */
    default TemplateApplication openDay(LocalDate date, UUID templateId, Actor actor) {
        return applyDayTemplate(templateId, List.of(date), actor);
    }

    /**
     * Предпросмотр ротации для периода: для каждой недели — шаблон недели по ротации от якоря, для
     * каждого дня периода — что будет создано или почему день пропускается. Ничего не меняет.
     *
     * @throws RotationNotConfiguredException если ротация пуста
     * @throws TemplateOverlapException если времена какого-то шаблона дня пересекаются при текущей длительности
     * @throws ValidationException если {@code to} раньше {@code from}
     * @throws AccessDeniedException если у актора нет чтения раздела «Расписание»
     */
    RotationPreview previewRotation(LocalDate from, LocalDate to, Actor actor);

    /**
     * Применяет план: создаёт слоты по выбранным для недель шаблонам (мастер мог поменять их
     * местами), пропуская уже открытые дни, и переносит якорь ротации на последнюю неделю периода —
     * следующий предпросмотр продолжит чередование с неё.
     *
     * @throws RotationNotConfiguredException если ротация пуста
     * @throws TemplateOverlapException если времена какого-то шаблона дня пересекаются при текущей длительности
     * @throws SlotOverlapException если слот пересёкся со слотом соседнего дня (слот через полночь)
     * @throws ValidationException если недели плана не совпадают с неделями периода, шаблон не из ротации
     *     или {@code to} раньше {@code from}
     * @throws AccessDeniedException если у актора нет изменения раздела «Расписание»
     */
    TemplateApplication applyRotation(ApplyRotation command, Actor actor);
}
