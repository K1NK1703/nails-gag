package gag.nails.api.internal.access;

import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.identity.AccessAdministration;
import gag.nails.identity.model.AccountAccessView;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Права аккаунтов (спецификация API, §7.7): роли и личные переопределения. Чтение — по уровню чтения раздела «Доступ»,
 * изменение — по уровню изменения; права и правило последнего администратора проверяет модуль. Ответ изменения — права
 * аккаунта после него; повтор того же изменения ничего не меняет и в журнал не попадает. Аккаунта или роли нет —
 * {@code 404}; изменение, после которого не осталось бы администратора, — {@code 409}. Раздел в пути — через дефис
 * ({@code booking-settings}), неизвестный — {@code 400 common.validation} с полем {@code section}.
 */
@RestController
@RequestMapping("/api/access")
class StaffAccessController {

    private final AccessAdministration access;

    StaffAccessController(AccessAdministration access) {
        this.access = access;
    }

    /** Аккаунты хотя бы с одной ролью или переопределением, по времени создания. */
    @GetMapping("/staff")
    List<AccountAccessView> staff(Actor actor) {
        return access.staff(actor);
    }

    /** Любой аккаунт: роли, переопределения и итог по всем разделам. */
    @GetMapping("/accounts/{id}")
    AccountAccessView account(@PathVariable UUID id, Actor actor) {
        return access.account(id, actor);
    }

    /** Назначение роли. */
    @PostMapping("/accounts/{id}/roles")
    AccountAccessView assignRole(@PathVariable UUID id, @Valid @RequestBody AssignRole body, Actor actor) {
        return access.assignRole(id, body.roleId(), actor);
    }

    /** Снятие роли; неназначенная роль — без изменений. */
    @DeleteMapping("/accounts/{id}/roles/{roleId}")
    AccountAccessView revokeRole(@PathVariable UUID id, @PathVariable UUID roleId, Actor actor) {
        return access.revokeRole(id, roleId, actor);
    }

    /** Личное переопределение уровня раздела: оно важнее ролей. */
    @PutMapping("/accounts/{id}/overrides/{section}")
    AccountAccessView setOverride(
            @PathVariable UUID id, @PathVariable Section section, @Valid @RequestBody SetOverride body, Actor actor) {
        return access.setOverride(id, section, body.level(), actor);
    }

    /** Снятие переопределения: уровень раздела снова по ролям. */
    @DeleteMapping("/accounts/{id}/overrides/{section}")
    AccountAccessView removeOverride(@PathVariable UUID id, @PathVariable Section section, Actor actor) {
        return access.setOverride(id, section, null, actor);
    }
}
