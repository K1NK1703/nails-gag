package gag.nails.api.internal.schedule;

import gag.nails.common.actor.Actor;
import gag.nails.schedule.ScheduleSettingsService;
import gag.nails.schedule.model.ScheduleSettingsView;
import gag.nails.schedule.model.SettingsChangeView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Настройки расписания (спецификация API, §7.5): текущая длительность слота — любому вошедшему, она видна при записи;
 * изменение — по уровню изменения раздела «Расписание», журнал — по уровню чтения. Права проверяет модуль.
 */
@RestController
@RequestMapping("/api/schedule/settings")
class ScheduleSettingsController {

    private final ScheduleSettingsService settings;

    ScheduleSettingsController(ScheduleSettingsService settings) {
        this.settings = settings;
    }

    /** Текущие настройки. Модулю актор не нужен, адресу — нужна сессия. */
    @GetMapping
    ScheduleSettingsView settings(Actor actor) {
        return settings.settings();
    }

    /**
     * Длительность новых слотов; уже созданные слоты не меняются. Изменение попадает в журнал, то же значение ничего не
     * меняет.
     */
    @PutMapping("/slot-duration")
    ScheduleSettingsView setSlotDuration(@Valid @RequestBody SetSlotDuration body, Actor actor) {
        return settings.setSlotDuration(body.minutes(), actor);
    }

    /** Журнал изменений настроек, новые первыми. */
    @GetMapping("/history")
    List<SettingsChangeView> history(Actor actor) {
        return settings.history(actor);
    }
}
