package gag.nails.api.internal.finance;

import gag.nails.common.actor.Actor;
import gag.nails.finance.FinanceCategories;
import gag.nails.finance.model.CategoryView;
import gag.nails.finance.model.EntryKind;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Категории доходов и расходов (спецификация финансов, §5): у каждого вида свой список и порядок; категория не
 * удаляется, а скрывается. Чтение — по уровню чтения раздела «Финансы», изменения — по уровню изменения; права и
 * правила проверяет модуль. Клиенту адреса отвечают {@code 404 common.not_found}, как несуществующие. Вид в адресе —
 * {@code entryKind=income|expense}: параметр {@code kind} в адресах уже занят видом документа.
 */
@RestController
@RequestMapping("/api/finance/categories")
class FinanceCategoriesController {

    private final FinanceCategories categories;

    FinanceCategoriesController(FinanceCategories categories) {
        this.categories = categories;
    }

    /** Все категории вида по порядку, со скрытыми (с пометкой). */
    @GetMapping
    List<CategoryView> list(@RequestParam EntryKind entryKind, Actor actor) {
        return categories.list(entryKind, actor);
    }

    /**
     * Новая видимая категория в конце списка своего вида. Пустое или длинное название — {@code 400}; название уже есть
     * в этом виде без учёта регистра — {@code 409 finance.category_name_taken}.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CategoryView create(@Valid @RequestBody CreateFinanceCategory body, Actor actor) {
        return categories.create(body.toCommand(), actor);
    }

    /**
     * Переименование; смена только регистра своего названия допустима. Категории нет — {@code 404}; название — как при
     * создании.
     */
    @PatchMapping("/{id}")
    CategoryView rename(@PathVariable UUID id, @Valid @RequestBody RenameFinanceCategory body, Actor actor) {
        return categories.rename(id, body.toCommand(), actor);
    }

    /** Скрывает категорию для новых записей; старые записи её показывают. Уже скрытая — без изменений. */
    @PostMapping("/{id}/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void hide(@PathVariable UUID id, Actor actor) {
        categories.hide(id, actor);
    }

    /** Снова предлагает категорию для новых записей; видимая — без изменений. */
    @PostMapping("/{id}/show")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void show(@PathVariable UUID id, Actor actor) {
        categories.show(id, actor);
    }

    /**
     * Порядок всех категорий вида, и скрытых. Устаревший список (категорию добавили) или чужой вид — {@code 400}, экран
     * перечитывает категории.
     */
    @PutMapping("/order")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reorder(@Valid @RequestBody ReorderFinanceCategories body, Actor actor) {
        categories.reorder(body.kind(), body.orderedIds(), actor);
    }
}
