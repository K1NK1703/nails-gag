package gag.nails.api.internal.finance;

import gag.nails.common.actor.Actor;
import gag.nails.finance.FinanceRecurring;
import gag.nails.finance.model.RecurringView;
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
 * Повторяющиеся ежемесячные записи (спецификация финансов, §4.2, §5): по шаблону ночная задача создаёт запись в его
 * число каждого месяца; созданная запись — обычная ручная. Чтение — по уровню чтения раздела «Финансы», изменения — по
 * уровню изменения; права и правила проверяет модуль. Клиенту адреса отвечают {@code 404 common.not_found}.
 */
@RestController
@RequestMapping("/api/finance/recurring")
class FinanceRecurringController {

    private final FinanceRecurring recurring;

    FinanceRecurringController(FinanceRecurring recurring) {
        this.recurring = recurring;
    }

    /** Все шаблоны по числу месяца. */
    @GetMapping
    List<RecurringView> list(Actor actor) {
        return recurring.list(actor);
    }

    /**
     * Новый шаблон вида своей категории, не на паузе; записи за уже наступившие дни создаются сразу. Категории нет —
     * {@code 404}; категория скрыта или поля вне пределов — {@code 400}.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RecurringView create(@Valid @RequestBody CreateRecurringEntry body, Actor actor) {
        return recurring.create(body.toCommand(), actor);
    }

    /**
     * Правка шаблона целиком; действует только на будущие записи: прошедшие дни по прежним полям досоздаются, перенос
     * начала назад и продление конца прошлое не догоняют. Шаблона или категории нет — {@code 404}; категория другого
     * вида или другая скрытая, поля вне пределов — {@code 400}.
     */
    @PatchMapping("/{id}")
    RecurringView update(@PathVariable UUID id, @Valid @RequestBody ChangeRecurringEntry body, Actor actor) {
        return recurring.update(id, body.toCommand(), actor);
    }

    /**
     * Пауза: записи за наступившие до неё дни создаются сразу, дни паузы потом не догоняются. Уже на паузе — без
     * изменений; шаблона нет — {@code 404}.
     */
    @PostMapping("/{id}/pause")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void pause(@PathVariable UUID id, Actor actor) {
        recurring.pause(id, actor);
    }

    /** Снимает паузу; дни паузы не догоняются. Не на паузе — без изменений; шаблона нет — {@code 404}. */
    @PostMapping("/{id}/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resume(@PathVariable UUID id, Actor actor) {
        recurring.resume(id, actor);
    }

    /** Удаляет шаблон; созданные им записи остаются. Шаблона нет — {@code 404}. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id, Actor actor) {
        recurring.delete(id, actor);
    }
}
