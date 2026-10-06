package gag.nails.identity.internal;

import gag.nails.clients.ClientQueries;
import gag.nails.clients.model.ClientView;
import gag.nails.common.actor.Actor;
import gag.nails.identity.AccountView;
import gag.nails.identity.Authenticated;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Собирает {@link Actor} и {@link AccountView} из базы: аккаунт, карточка клиента, итоговые уровни по
 * разделам. Делается на каждый запрос, чтобы изменения прав действовали сразу.
 */
@Component
class ActorAssembly {

    private final AccessResolver resolver;
    private final LoginMethodRepository loginMethods;
    private final ClientQueries clients;

    ActorAssembly(AccessResolver resolver, LoginMethodRepository loginMethods, ClientQueries clients) {
        this.resolver = resolver;
        this.loginMethods = loginMethods;
        this.clients = clients;
    }

    Optional<ClientView> card(AccountEntity account) {
        return clients.byAccountId(account.id());
    }

    /**
     * @param current способ входа, чьи имя и аватарка показываются: у сессии — которым вошли, в
     *     профиле — использованный последним
     */
    Authenticated assemble(AccountEntity account, @Nullable LoginMethodEntity current, Optional<ClientView> card) {
        var effective = resolver.effective(account.id());
        var clientId = card.map(ClientView::id).orElse(null);
        var methods = loginMethods.findAllByAccountIdOrderByLinkedAtAscIdAsc(account.id()).stream()
                .map(LoginMethodEntity::toView)
                .toList();
        var view = new AccountView(
                account.id(),
                clientId,
                current == null ? null : current.displayName(),
                current == null ? null : current.avatarUrl(),
                methods,
                effective,
                account.createdAt());
        return new Authenticated(Actor.of(account.id(), clientId, effective), view);
    }
}
