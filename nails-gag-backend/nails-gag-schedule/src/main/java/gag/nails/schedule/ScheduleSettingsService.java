package gag.nails.schedule;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import java.util.List;

/** Настройки расписания. Чтение открыто (длительность слота показывается при записи), изменение — только мастер. */
public interface ScheduleSettingsService {

    ScheduleSettingsView settings();

    /**
     * Меняет длительность новых слотов (15–720 минут). Уже созданные слоты не трогает. Изменение
     * попадает в журнал; повторная установка того же значения ничего не делает.
     *
     * @throws ValidationException если значение вне диапазона
     * @throws AccessDeniedException если действует не мастер
     */
    ScheduleSettingsView setSlotDuration(int minutes, Actor actor);

    /**
     * Журнал изменений настроек, новые первыми. Только мастер.
     *
     * @throws AccessDeniedException если действует не мастер
     */
    List<SettingsChangeView> history(Actor actor);
}
