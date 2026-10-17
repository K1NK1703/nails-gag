package gag.nails.api.internal.schedule;

import gag.nails.api.internal.http.PeriodParams;
import gag.nails.common.actor.Actor;
import gag.nails.schedule.ScheduleService;
import gag.nails.schedule.ScheduleTemplates;
import gag.nails.schedule.command.ApplyRotation;
import gag.nails.schedule.command.SetRotation;
import gag.nails.schedule.model.RotationPreview;
import gag.nails.schedule.model.RotationView;
import gag.nails.schedule.model.TemplateApplication;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ротация шаблонов недель (спецификация API, §7.5): чтение и предпросмотр — по уровню чтения раздела «Расписание»,
 * настройка и применение — по уровню изменения; права проверяет модуль. Период {@code from}/{@code to} — включительно
 * и не длиннее {@value PeriodParams#MAX_DAYS} дней.
 */
@RestController
@RequestMapping("/api/schedule/rotation")
class RotationController {

    private final ScheduleService schedule;
    private final ScheduleTemplates templates;

    RotationController(ScheduleService schedule, ScheduleTemplates templates) {
        this.schedule = schedule;
        this.templates = templates;
    }

    /** Текущая ротация; пустой {@code weekTemplateIds} — ротация не настроена. */
    @GetMapping
    RotationView rotation(Actor actor) {
        return templates.rotation(actor);
    }

    /** Ротация целиком: порядок шаблонов недель и якорь — понедельник с индексом шаблона, который на него приходится. */
    @PutMapping
    RotationView setRotation(@Valid @RequestBody SetRotation body, Actor actor) {
        return templates.setRotation(body, actor);
    }

    /**
     * Что создаст применение ротации к периоду: шаблон недели для каждой недели и судьба каждого дня. Ничего не меняет;
     * ненастроенная ротация — {@code 409}.
     */
    @GetMapping("/preview")
    RotationPreview preview(@RequestParam LocalDate from, @RequestParam LocalDate to, Actor actor) {
        PeriodParams.check(from, to);
        return schedule.previewRotation(from, to, actor);
    }

    /**
     * Применение плана из предпросмотра, возможно с переставленными шаблонами недель: создаёт слоты, пропуская уже
     * открытые дни, и переносит якорь на последнюю неделю периода.
     */
    @PostMapping("/apply")
    TemplateApplication apply(@Valid @RequestBody ApplyRotation body, Actor actor) {
        PeriodParams.check(body.from(), body.to());
        return schedule.applyRotation(body, actor);
    }
}
