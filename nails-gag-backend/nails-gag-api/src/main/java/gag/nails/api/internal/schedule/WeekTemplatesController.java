package gag.nails.api.internal.schedule;

import gag.nails.common.actor.Actor;
import gag.nails.schedule.ScheduleTemplates;
import gag.nails.schedule.command.CreateWeekTemplate;
import gag.nails.schedule.command.UpdateWeekTemplate;
import gag.nails.schedule.model.WeekTemplateView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Шаблоны недели (спецификация API, §7.5): рабочие дни недели и шаблон дня для каждого, день не в карте — выходной.
 * Чтение — по уровню чтения раздела «Расписание», ведение — по уровню изменения; права проверяет модуль.
 */
@RestController
@RequestMapping("/api/schedule/week-templates")
class WeekTemplatesController {

    private final ScheduleTemplates templates;

    WeekTemplatesController(ScheduleTemplates templates) {
        this.templates = templates;
    }

    /** Шаблоны недели по имени. */
    @GetMapping
    List<WeekTemplateView> weekTemplates(Actor actor) {
        return templates.weekTemplates(actor);
    }

    /** Новый шаблон недели; занятое имя — {@code 409}, неизвестный шаблон дня — {@code 404}. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    WeekTemplateView create(@Valid @RequestBody CreateWeekTemplate body, Actor actor) {
        return templates.createWeekTemplate(body, actor);
    }

    /** Новое имя и полная новая карта рабочих дней; уже созданные слоты не меняются. */
    @PatchMapping("/{id}")
    WeekTemplateView update(@PathVariable UUID id, @Valid @RequestBody UpdateWeekTemplate body, Actor actor) {
        return templates.updateWeekTemplate(id, body, actor);
    }

    /** Удаление шаблона; шаблон из ротации — {@code 409}. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id, Actor actor) {
        templates.deleteWeekTemplate(id, actor);
    }
}
