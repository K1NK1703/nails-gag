package gag.nails.api.internal.schedule;

import gag.nails.common.actor.Actor;
import gag.nails.schedule.ScheduleTemplates;
import gag.nails.schedule.command.CreateDayTemplate;
import gag.nails.schedule.command.UpdateDayTemplate;
import gag.nails.schedule.model.DayTemplateView;
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
 * Шаблоны дня (спецификация API, §7.5): времена начала слотов, по которым открываются дни. Чтение — по уровню чтения
 * раздела «Расписание», ведение — по уровню изменения; права проверяет модуль. Слоты, уже созданные по шаблону, правка
 * и удаление шаблона не трогают.
 */
@RestController
@RequestMapping("/api/schedule/day-templates")
class DayTemplatesController {

    private final ScheduleTemplates templates;

    DayTemplatesController(ScheduleTemplates templates) {
        this.templates = templates;
    }

    /** Шаблоны дня в порядке списка. */
    @GetMapping
    List<DayTemplateView> dayTemplates(Actor actor) {
        return templates.dayTemplates(actor);
    }

    /**
     * Новый шаблон в конце списка; если шаблона по умолчанию нет, им становится новый. Занятое имя или времена,
     * пересекающиеся при текущей длительности слота, — {@code 409}.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    DayTemplateView create(@Valid @RequestBody CreateDayTemplate body, Actor actor) {
        return templates.createDayTemplate(body, actor);
    }

    /** Новое имя и полный новый набор времён. */
    @PatchMapping("/{id}")
    DayTemplateView update(@PathVariable UUID id, @Valid @RequestBody UpdateDayTemplate body, Actor actor) {
        return templates.updateDayTemplate(id, body, actor);
    }

    /**
     * Удаление шаблона; удалённый шаблон по умолчанию не заменяется другим. Шаблон из шаблона недели — {@code 409}.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id, Actor actor) {
        templates.deleteDayTemplate(id, actor);
    }

    /** Шаблон становится шаблоном по умолчанию, прежний перестаёт им быть. */
    @PostMapping("/{id}/set-default")
    DayTemplateView setDefault(@PathVariable UUID id, Actor actor) {
        return templates.setDefaultDayTemplate(id, actor);
    }
}
