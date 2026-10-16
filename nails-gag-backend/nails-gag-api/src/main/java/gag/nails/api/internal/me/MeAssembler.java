package gag.nails.api.internal.me;

import gag.nails.clients.ClientQueries;
import gag.nails.common.actor.Actor;
import gag.nails.identity.Accounts;
import org.springframework.stereotype.Component;

/**
 * Сборка {@link Me} — одна на все адреса, что его отдают: свой аккаунт и своя карточка клиента, если она есть. Два
 * чтения в транзакциях модулей, общей нет: контроллеры транзакций не открывают. Публичный: им же пользуются адреса
 * входа.
 */
@Component
public final class MeAssembler {

    private final Accounts accounts;
    private final ClientQueries clients;

    MeAssembler(Accounts accounts, ClientQueries clients) {
        this.accounts = accounts;
        this.clients = clients;
    }

    /** Профиль актора; права на чтение своей карточки проверяет модуль клиентов. */
    public Me of(Actor actor) {
        var account = accounts.me(actor);
        var clientId = account.clientId();
        return new Me(account, clientId == null ? null : clients.get(clientId, actor));
    }
}
