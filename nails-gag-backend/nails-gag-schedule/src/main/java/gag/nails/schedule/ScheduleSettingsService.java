package gag.nails.schedule;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.schedule.model.ScheduleSettingsView;
import gag.nails.schedule.model.SettingsChangeView;
import java.util.List;

/**
 * Настройки расписания. Текущая длительность открыта (показывается при записи), изменение требует
 * уровня изменения раздела «Расписание», журнал — уровня чтения.
 */
public interface ScheduleSettingsService {

    /** Наименьшая допустимая длительность слота в минутах. */
    int MIN_SLOT_DURATION_MINUTES = 15;

    /** Наибольшая допустимая длительность слота в минутах (12 часов). */
    int MAX_SLOT_DURATION_MINUTES = 720;

    /** Текущие настройки: длительность, с которой открываются новые слоты. */
    ScheduleSettingsView settings();

    /**
     * Меняет длительность новых слотов ({@value #MIN_SLOT_DURATION_MINUTES}–{@value
     * #MAX_SLOT_DURATION_MINUTES} минут). Уже созданные слоты не трогает. Изменение попадает в
     * журнал; повторная установка того же значения ничего не делает.
     *
     * @throws ValidationException если значение вне диапазона
     * @throws AccessDeniedException если у актора нет изменения раздела «Расписание»
     */
    ScheduleSettingsView setSlotDuration(int minutes, Actor actor);

    /**
     * Журнал изменений настроек, новые первыми. Нужен уровень чтения раздела «Расписание».
     *
     * @throws AccessDeniedException если у актора нет чтения раздела «Расписание»
     */
    List<SettingsChangeView> history(Actor actor);
}
