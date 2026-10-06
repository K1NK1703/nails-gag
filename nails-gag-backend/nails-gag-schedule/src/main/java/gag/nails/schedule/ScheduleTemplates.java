package gag.nails.schedule;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.schedule.error.DayTemplateInUseException;
import gag.nails.schedule.error.DayTemplateNotFoundException;
import gag.nails.schedule.error.TemplateNameTakenException;
import gag.nails.schedule.error.TemplateOverlapException;
import gag.nails.schedule.error.WeekTemplateInUseException;
import gag.nails.schedule.error.WeekTemplateNotFoundException;
import java.util.List;
import java.util.UUID;

/** Шаблоны дня и недели и ротация недель. Чтение требует уровня чтения раздела «Расписание», изменение —
 * уровня изменения. */
public interface ScheduleTemplates {

    /**
     * Шаблоны дня в порядке списка.
     *
     * @throws AccessDeniedException если у актора нет чтения раздела «Расписание»
     */
    List<DayTemplateView> dayTemplates(Actor actor);

    /**
     * Создаёт шаблон дня в конце списка. Если шаблона по умолчанию нет (список пуст или он удалён),
     * созданный становится им.
     *
     * @throws TemplateNameTakenException если имя занято
     * @throws TemplateOverlapException если времена пересекаются при текущей длительности слота
     * @throws ValidationException если имя пустое или времена повторяются
     * @throws AccessDeniedException если у актора нет изменения раздела «Расписание»
     */
    DayTemplateView createDayTemplate(CreateDayTemplate command, Actor actor);

    /**
     * Меняет имя и набор времён шаблона. Уже созданные по нему слоты не меняются.
     *
     * @throws DayTemplateNotFoundException если шаблона нет
     * @throws TemplateNameTakenException если имя занято другим шаблоном
     * @throws TemplateOverlapException если времена пересекаются при текущей длительности слота
     * @throws ValidationException если имя пустое или времена повторяются
     * @throws AccessDeniedException если у актора нет изменения раздела «Расписание»
     */
    DayTemplateView updateDayTemplate(UUID templateId, UpdateDayTemplate command, Actor actor);

    /**
     * Удаляет шаблон дня. Созданные по нему слоты остаются. Если удалён шаблон по умолчанию, по
     * умолчанию не остаётся ни одного.
     *
     * @throws DayTemplateNotFoundException если шаблона нет
     * @throws DayTemplateInUseException если на шаблон ссылается шаблон недели
     * @throws AccessDeniedException если у актора нет изменения раздела «Расписание»
     */
    void deleteDayTemplate(UUID templateId, Actor actor);

    /**
     * Делает шаблон шаблоном по умолчанию; прежний перестаёт им быть.
     *
     * @throws DayTemplateNotFoundException если шаблона нет
     * @throws AccessDeniedException если у актора нет изменения раздела «Расписание»
     */
    DayTemplateView setDefaultDayTemplate(UUID templateId, Actor actor);

    /**
     * Шаблоны недели по имени.
     *
     * @throws AccessDeniedException если у актора нет чтения раздела «Расписание»
     */
    List<WeekTemplateView> weekTemplates(Actor actor);

    /**
     * Создаёт шаблон недели.
     *
     * @throws TemplateNameTakenException если имя занято
     * @throws DayTemplateNotFoundException если какого-то шаблона дня из карты нет
     * @throws ValidationException если имя пустое
     * @throws AccessDeniedException если у актора нет изменения раздела «Расписание»
     */
    WeekTemplateView createWeekTemplate(CreateWeekTemplate command, Actor actor);

    /**
     * Меняет имя и карту рабочих дней. Уже созданные слоты не меняются.
     *
     * @throws WeekTemplateNotFoundException если шаблона нет
     * @throws TemplateNameTakenException если имя занято другим шаблоном
     * @throws DayTemplateNotFoundException если какого-то шаблона дня из карты нет
     * @throws ValidationException если имя пустое
     * @throws AccessDeniedException если у актора нет изменения раздела «Расписание»
     */
    WeekTemplateView updateWeekTemplate(UUID templateId, UpdateWeekTemplate command, Actor actor);

    /**
     * Удаляет шаблон недели.
     *
     * @throws WeekTemplateNotFoundException если шаблона нет
     * @throws WeekTemplateInUseException если шаблон входит в ротацию
     * @throws AccessDeniedException если у актора нет изменения раздела «Расписание»
     */
    void deleteWeekTemplate(UUID templateId, Actor actor);

    /**
     * Текущая ротация; пустой список шаблонов — ротация не настроена.
     *
     * @throws AccessDeniedException если у актора нет чтения раздела «Расписание»
     */
    RotationView rotation(Actor actor);

    /**
     * Задаёт ротацию целиком: порядок шаблонов недель и якорь.
     *
     * @throws WeekTemplateNotFoundException если какого-то шаблона недели нет
     * @throws ValidationException если список пуст, якорь не понедельник или индекс вне списка
     * @throws AccessDeniedException если у актора нет изменения раздела «Расписание»
     */
    RotationView setRotation(SetRotation command, Actor actor);
}
