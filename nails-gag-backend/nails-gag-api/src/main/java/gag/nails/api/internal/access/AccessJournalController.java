package gag.nails.api.internal.access;

import gag.nails.common.actor.Actor;
import gag.nails.common.paging.Paged;
import gag.nails.common.paging.Paging;
import gag.nails.identity.AccessAdministration;
import gag.nails.identity.model.AccessChangeView;
import gag.nails.identity.model.LoginEventView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Журналы прав и входов (спецификация API, §7.7), постранично, новые первыми; нужен уровень чтения раздела «Доступ»,
 * его проверяет модуль. Номер и размер страницы проверяет {@link Paging}.
 */
@RestController
@RequestMapping("/api/access")
class AccessJournalController {

    /** Номер страницы по умолчанию — первая. */
    private static final String FIRST_PAGE = "0";

    /** Размер страницы по умолчанию; наибольший — {@link Paging#MAX_SIZE}, больше — {@code 400}. */
    private static final String DEFAULT_PAGE_SIZE = "50";

    private final AccessAdministration access;

    AccessJournalController(AccessAdministration access) {
        this.access = access;
    }

    /** Журнал прав: роли, их назначение и переопределения — кто, кому и что изменил. */
    @GetMapping("/journal")
    Paged<AccessChangeView> accessJournal(
            @RequestParam(defaultValue = FIRST_PAGE) int page,
            @RequestParam(defaultValue = DEFAULT_PAGE_SIZE) int size,
            Actor actor) {
        return access.accessJournal(new Paging(page, size), actor);
    }

    /** Журнал входов, удачных и нет. */
    @GetMapping("/login-journal")
    Paged<LoginEventView> loginJournal(
            @RequestParam(defaultValue = FIRST_PAGE) int page,
            @RequestParam(defaultValue = DEFAULT_PAGE_SIZE) int size,
            Actor actor) {
        return access.loginJournal(new Paging(page, size), actor);
    }
}
