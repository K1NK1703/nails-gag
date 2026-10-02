package gag.nails.schedule;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.schedule.error.DayTemplateInUseException;
import gag.nails.schedule.error.DayTemplateNotFoundException;
import gag.nails.schedule.error.TemplateNameTakenException;
import gag.nails.schedule.error.TemplateOverlapException;
import java.util.List;
import java.util.UUID;

/** Шаблоны дня и недели и ротация недель. Всё — только мастер. */
public interface ScheduleTemplates {

    /**
     * Шаблоны дня в порядке списка.
     *
     * @throws AccessDeniedException если действует не мастер
     */
    List<DayTemplateView> dayTemplates(Actor actor);

    /**
     * Создаёт шаблон дня в конце списка. Первый созданный шаблон становится шаблоном по умолчанию.
     *
     * @throws TemplateNameTakenException если имя занято
     * @throws TemplateOverlapException если времена пересекаются при текущей длительности слота
     * @throws ValidationException если имя пустое или времена повторяются
     * @throws AccessDeniedException если действует не мастер
     */
    DayTemplateView createDayTemplate(CreateDayTemplate command, Actor actor);

    /**
     * Меняет имя и набор времён шаблона. Уже созданные по нему слоты не меняются.
     *
     * @throws DayTemplateNotFoundException если шаблона нет
     * @throws TemplateNameTakenException если имя занято другим шаблоном
     * @throws TemplateOverlapException если времена пересекаются при текущей длительности слота
     * @throws ValidationException если имя пустое или времена повторяются
     * @throws AccessDeniedException если действует не мастер
     */
    DayTemplateView updateDayTemplate(UUID templateId, UpdateDayTemplate command, Actor actor);

    /**
     * Удаляет шаблон дня. Созданные по нему слоты остаются.
     *
     * @throws DayTemplateNotFoundException если шаблона нет
     * @throws DayTemplateInUseException если на шаблон ссылается шаблон недели
     * @throws AccessDeniedException если действует не мастер
     */
    void deleteDayTemplate(UUID templateId, Actor actor);

    /**
     * Делает шаблон шаблоном по умолчанию; прежний перестаёт им быть.
     *
     * @throws DayTemplateNotFoundException если шаблона нет
     * @throws AccessDeniedException если действует не мастер
     */
    DayTemplateView setDefaultDayTemplate(UUID templateId, Actor actor);
}
