package gag.nails.api.internal.finance;

import gag.nails.api.internal.http.PageDefaults;
import gag.nails.common.actor.Actor;
import gag.nails.common.paging.Paged;
import gag.nails.common.paging.Paging;
import gag.nails.common.time.ReportPeriod;
import gag.nails.finance.FinanceEntries;
import gag.nails.finance.model.EntryChangeView;
import gag.nails.finance.model.EntryFilter;
import gag.nails.finance.model.EntryKind;
import gag.nails.finance.model.EntryView;
import gag.nails.finance.model.QuickCombo;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ручные доходы и расходы (спецификация финансов, §5): внесение, правка, удаление, история изменений, список периода
 * постранично, быстрый ввод. Чтение — по уровню чтения раздела «Финансы», изменения — по уровню изменения; права и
 * правила проверяет модуль. На корректный запрос клиенту адреса отвечают {@code 404 common.not_found}, как
 * несуществующие. Период {@code from}/{@code to} — дни операций в поясе салона, обе границы включительно, по правилам
 * {@link ReportPeriod} — его проверяет модуль ({@code 400} с полем {@code from} или {@code to}).
 */
@RestController
@RequestMapping("/api/finance/entries")
class FinanceEntriesController {

    private final FinanceEntries entries;

    FinanceEntriesController(FinanceEntries entries) {
        this.entries = entries;
    }

    /**
     * Записи периода по дню операции, новые дни первыми, внутри дня — позже внесённые первыми; если заданы, только вида
     * {@code entryKind} и категории {@code categoryId}. Период вне правил {@link ReportPeriod} — {@code 400}.
     */
    @GetMapping
    Paged<EntryView> list(
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(required = false) @Nullable EntryKind entryKind,
            @RequestParam(required = false) @Nullable UUID categoryId,
            @RequestParam(defaultValue = PageDefaults.FIRST_PAGE) int page,
            @RequestParam(defaultValue = PageDefaults.SIZE) int size,
            Actor actor) {
        return entries.list(new EntryFilter(from, to, entryKind, categoryId), new Paging(page, size), actor);
    }

    /**
     * Быстрый ввод: до пяти последних различных пар «категория + сумма» ручных записей вида; без скрытых категорий и
     * без записей, созданных повторяющимся шаблоном.
     */
    @GetMapping("/recent")
    List<QuickCombo> recent(@RequestParam EntryKind entryKind, Actor actor) {
        return entries.recentCombos(entryKind, actor);
    }

    /** Запись; удалённой нет — {@code 404}, её история остаётся. */
    @GetMapping("/{id}")
    EntryView get(@PathVariable UUID id, Actor actor) {
        return entries.get(id, actor);
    }

    /** Журнал изменений записи от создания, со снимками «было» и «стало»; у удалённой записи история остаётся. */
    @GetMapping("/{id}/history")
    List<EntryChangeView> history(@PathVariable UUID id, Actor actor) {
        return entries.history(id, actor);
    }

    /**
     * Новая запись вида своей категории. Категории нет — {@code 404}; категория скрыта, сумма, день или комментарий вне
     * пределов — {@code 400}.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    EntryView add(@Valid @RequestBody AddFinanceEntry body, Actor actor) {
        return entries.add(body.toCommand(), actor);
    }

    /**
     * Правка записи целиком. Записи или категории нет — {@code 404}; категория другого вида или другая скрытая,
     * перенос записи шаблона на день, где уже есть запись того же шаблона, поля вне пределов — {@code 400}.
     */
    @PatchMapping("/{id}")
    EntryView update(@PathVariable UUID id, @Valid @RequestBody ChangeFinanceEntry body, Actor actor) {
        return entries.update(id, body.toCommand(), actor);
    }

    /** Удаляет запись; её история остаётся. Записи нет — {@code 404}. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id, Actor actor) {
        entries.delete(id, actor);
    }
}
