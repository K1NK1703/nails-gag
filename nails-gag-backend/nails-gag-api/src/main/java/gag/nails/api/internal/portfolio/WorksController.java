package gag.nails.api.internal.portfolio;

import gag.nails.common.actor.Actor;
import gag.nails.portfolio.PortfolioQueries;
import gag.nails.portfolio.PortfolioService;
import gag.nails.portfolio.model.WorkView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Ведение работ портфолио (спецификация файлов и портфолио, §6.2): чтение — по уровню чтения раздела «Портфолио»,
 * изменения — по уровню изменения; права, правила источника и согласия проверяет модуль. Клиенту адреса отвечают
 * {@code 404 common.not_found}, как несуществующие.
 */
@RestController
@RequestMapping("/api/portfolio")
class WorksController {

    private final PortfolioQueries queries;
    private final PortfolioService portfolio;

    WorksController(PortfolioQueries queries, PortfolioService portfolio) {
        this.queries = queries;
        this.portfolio = portfolio;
    }

    /** Все работы по порядку лендинга, со скрытыми; у каждой — опубликована ли она и состояние согласия. */
    @GetMapping("/works")
    List<WorkView> all(Actor actor) {
        return queries.all(actor);
    }

    /** Работа со скрытыми полями: источник, запись, клиент, согласие. */
    @GetMapping("/works/{id}")
    WorkView get(@PathVariable UUID id, Actor actor) {
        return queries.get(id, actor);
    }

    /**
     * Новая работа из загруженного этим же аккаунтом фото: видима и встаёт в конец. У {@code CLIENT} клиент берётся из
     * записи {@code bookingId}, у остальных источников записи нет. Чужое, занятое или не то фото, неверная пара
     * источника и записи, длинный текст — {@code 400}.
     */
    @PostMapping("/works")
    @ResponseStatus(HttpStatus.CREATED)
    WorkView add(@Valid @RequestBody AddPortfolioWork body, Actor actor) {
        return portfolio.add(body.toCommand(), actor);
    }

    /** Правка описания, подписи, источника и записи; правила — как при добавлении. */
    @PatchMapping("/works/{id}")
    WorkView update(@PathVariable UUID id, @Valid @RequestBody UpdatePortfolioWork body, Actor actor) {
        return portfolio.update(id, body.toCommand(), actor);
    }

    /** Мастер показывает работу; на лендинге она появится, если позволяет согласие. Уже видимая — без изменений. */
    @PostMapping("/works/{id}/show")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void show(@PathVariable UUID id, Actor actor) {
        portfolio.show(id, actor);
    }

    /** Мастер скрывает работу с лендинга; уже скрытая — без изменений. */
    @PostMapping("/works/{id}/hide")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void hide(@PathVariable UUID id, Actor actor) {
        portfolio.hide(id, actor);
    }

    /**
     * Порядок всех работ: список — каждая работа ровно один раз. Устаревший список (работу добавили или удалили) —
     * {@code 400}, экран перечитывает работы.
     */
    @PutMapping("/order")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reorder(@Valid @RequestBody ReorderWorks body, Actor actor) {
        portfolio.reorder(body.orderedWorkIds(), actor);
    }

    /** Удаляет работу и её фото. */
    @DeleteMapping("/works/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id, Actor actor) {
        portfolio.delete(id, actor);
    }
}
