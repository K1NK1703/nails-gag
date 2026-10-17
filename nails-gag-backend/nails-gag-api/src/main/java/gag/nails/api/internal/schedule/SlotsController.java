package gag.nails.api.internal.schedule;

import gag.nails.api.internal.http.PeriodParams;
import gag.nails.common.actor.Actor;
import gag.nails.schedule.ScheduleQueries;
import gag.nails.schedule.ScheduleService;
import gag.nails.schedule.model.SlotView;
import gag.nails.schedule.model.TemplateApplication;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Слоты расписания (спецификация API, §7.5): свободные — любому вошедшему, для выбора времени записи; все слоты, их
 * ведение и открытие дней по шаблону дня — по уровню раздела «Расписание», его проверяет модуль. Даты и время — в
 * поясе салона, период {@code from}/{@code to} — включительно и не длиннее {@value PeriodParams#MAX_DAYS} дней.
 */
@RestController
@RequestMapping("/api/schedule")
class SlotsController {

    private final ScheduleQueries queries;
    private final ScheduleService schedule;

    SlotsController(ScheduleQueries queries, ScheduleService schedule) {
        this.queries = queries;
        this.schedule = schedule;
    }

    /**
     * Свободные слоты периода по времени начала, в том числе прошедшие и уже закрытые для записи правилами записей.
     * Модулю актор не нужен: параметр {@code actor} лишь требует сессию — без неё {@code 401}.
     */
    @GetMapping("/free-slots")
    List<SlotView> freeSlots(@RequestParam LocalDate from, @RequestParam LocalDate to, Actor actor) {
        PeriodParams.check(from, to);
        return queries.freeSlots(from, to);
    }

    /** Все слоты периода, занятые — с записью; нужен уровень чтения раздела «Расписание». */
    @GetMapping("/slots")
    List<SlotView> slots(@RequestParam LocalDate from, @RequestParam LocalDate to, Actor actor) {
        PeriodParams.check(from, to);
        return queries.slotsOfPeriod(from, to, actor);
    }

    /**
     * Новый слот с текущей длительностью; нужен уровень изменения раздела «Расписание». Пересечение с другим слотом —
     * {@code 409}.
     */
    @PostMapping("/slots")
    @ResponseStatus(HttpStatus.CREATED)
    SlotView addSlot(@Valid @RequestBody AddSlot body, Actor actor) {
        return schedule.addSlot(body.date(), body.startTime(), actor);
    }

    /**
     * Свободный слот на другое время того же дня; нужен уровень изменения раздела «Расписание». Занятый слот или
     * пересечение с другим — {@code 409}.
     */
    @PostMapping("/slots/{id}/move")
    SlotView moveSlot(@PathVariable UUID id, @Valid @RequestBody MoveSlot body, Actor actor) {
        return schedule.moveSlot(id, body.startTime(), actor);
    }

    /** Закрытие свободного слота; нужен уровень изменения раздела «Расписание». Занятый слот — {@code 409}. */
    @DeleteMapping("/slots/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteSlot(@PathVariable UUID id, Actor actor) {
        schedule.deleteSlot(id, actor);
    }

    /**
     * Открытие дней по шаблону дня с текущей длительностью; нужен уровень изменения раздела «Расписание». Дни, где уже
     * есть слот, пропускаются и перечисляются в {@code skipped}.
     */
    @PostMapping("/days/apply-template")
    TemplateApplication applyDayTemplate(@Valid @RequestBody ApplyDayTemplate body, Actor actor) {
        return schedule.applyDayTemplate(body.templateId(), body.dates(), actor);
    }
}
