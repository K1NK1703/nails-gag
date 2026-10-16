package gag.nails.api.internal.me;

import gag.nails.api.internal.security.SessionCookie;
import gag.nails.common.actor.Actor;
import gag.nails.identity.Accounts;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Профиль вошедшего (спецификация API, §7.1): чтение и удаление своего аккаунта. */
@RestController
@RequestMapping("/api/me")
class MeController {

    private final MeAssembler me;
    private final Accounts accounts;
    private final SessionCookie sessionCookie;

    MeController(MeAssembler me, Accounts accounts, SessionCookie sessionCookie) {
        this.me = me;
        this.accounts = accounts;
        this.sessionCookie = sessionCookie;
    }

    @GetMapping
    Me me(Actor actor) {
        return me.of(actor);
    }

    /**
     * Удаление своего аккаунта: модуль обезличивает карточку и закрывает все сессии, cookie снимается здесь. Отказ
     * (последний администратор) cookie не трогает — человек остаётся вошедшим.
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(Actor actor, HttpServletResponse response) {
        accounts.delete(actor);
        sessionCookie.clear(response);
    }
}
