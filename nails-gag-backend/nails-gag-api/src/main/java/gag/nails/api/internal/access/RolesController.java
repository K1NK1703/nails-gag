package gag.nails.api.internal.access;

import gag.nails.common.actor.Actor;
import gag.nails.identity.AccessAdministration;
import gag.nails.identity.command.CreateRole;
import gag.nails.identity.command.UpdateRole;
import gag.nails.identity.model.RoleView;
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
 * Конструктор ролей (спецификация API, §7.7): чтение — по уровню чтения раздела «Доступ», изменение — по уровню
 * изменения; права и правило последнего администратора проверяет модуль. Роли нет — {@code 404}.
 */
@RestController
@RequestMapping("/api/access/roles")
class RolesController {

    private final AccessAdministration access;

    RolesController(AccessAdministration access) {
        this.access = access;
    }

    /** Все роли по коду, встроенные тоже. */
    @GetMapping
    List<RoleView> roles(Actor actor) {
        return access.roles(actor);
    }

    /** Новая роль; разделы с уровнем {@code NONE} отбрасываются. Занятый код — {@code 409}. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RoleView create(@Valid @RequestBody CreateRole body, Actor actor) {
        return access.createRole(body, actor);
    }

    /**
     * Название и полный новый набор уровней; код не меняется. Встроенные роли менять можно, но понизить «Доступ» у
     * «Разработчика» — {@code 409}; изменение, после которого не осталось бы администратора, — тоже {@code 409}.
     */
    @PatchMapping("/{id}")
    RoleView update(@PathVariable UUID id, @Valid @RequestBody UpdateRole body, Actor actor) {
        return access.updateRole(id, body, actor);
    }

    /** Удаление роли. Встроенная роль или роль, назначенная хотя бы одному аккаунту, — {@code 409}. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id, Actor actor) {
        access.deleteRole(id, actor);
    }
}
