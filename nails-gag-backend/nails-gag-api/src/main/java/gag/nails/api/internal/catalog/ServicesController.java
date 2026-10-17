package gag.nails.api.internal.catalog;

import gag.nails.catalog.CatalogQueries;
import gag.nails.catalog.CatalogService;
import gag.nails.catalog.command.CreateService;
import gag.nails.catalog.command.UpdateService;
import gag.nails.catalog.model.ServiceView;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Услуги каталога (спецификация API, §7.3): чтение — любому вошедшему, ведение — по уровню изменения раздела
 * «Каталог», его проверяет модуль.
 */
@RestController
@RequestMapping("/api/services")
class ServicesController {

    private final CatalogQueries queries;
    private final CatalogService catalog;

    ServicesController(CatalogQueries queries, CatalogService catalog) {
        this.queries = queries;
        this.catalog = catalog;
    }

    /** С чтением раздела «Каталог» — все услуги, со скрытыми; иначе — активные, для формы записи. */
    @GetMapping
    List<ServiceView> services(Actor actor) {
        return actor.canRead(Section.CATALOG) ? queries.all(actor) : queries.activeServices();
    }

    /** Услуга, в том числе скрытая. Модулю актор не нужен, адресу — нужна сессия, как и списку. */
    @GetMapping("/{id}")
    ServiceView service(@PathVariable UUID id, Actor actor) {
        return queries.get(id);
    }

    /** Новая услуга становится последней в прайсе; нужен уровень изменения раздела «Каталог». */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ServiceView create(@Valid @RequestBody CreateService body, Actor actor) {
        return catalog.create(body, actor);
    }

    /** Правка услуги; вид услуги не меняется. Нужен уровень изменения раздела «Каталог». */
    @PatchMapping("/{id}")
    ServiceView update(@PathVariable UUID id, @Valid @RequestBody UpdateService body, Actor actor) {
        return catalog.update(id, body, actor);
    }

    /** Скрытая услуга снова доступна для новых записей; нужен уровень изменения раздела «Каталог». */
    @PostMapping("/{id}/activate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void activate(@PathVariable UUID id, Actor actor) {
        catalog.activate(id, actor);
    }

    /**
     * Скрытая услуга остаётся в истории записей, но недоступна для новых; нужен уровень изменения раздела «Каталог».
     */
    @PostMapping("/{id}/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deactivate(@PathVariable UUID id, Actor actor) {
        catalog.deactivate(id, actor);
    }

    /**
     * Порядок прайса: перечисленные услуги — первыми в порядке списка, остальные — за ними в прежнем порядке. Нужен
     * уровень изменения раздела «Каталог»; повтор в списке — {@code 400}.
     */
    @PutMapping("/order")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reorder(@Valid @RequestBody ReorderServices body, Actor actor) {
        catalog.reorder(body.orderedServiceIds(), actor);
    }
}
