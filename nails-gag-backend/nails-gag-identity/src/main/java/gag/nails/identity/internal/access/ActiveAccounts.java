package gag.nails.identity.internal.access;

import gag.nails.clients.ClientQueries;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Активен ли аккаунт: его карточка клиента не заблокирована и не обезличена; аккаунт без карточки активен.
 * Одно правило для последнего администратора ({@link AccessGuard}) и получателей ({@link
 * DefaultStaffDirectory}). У обезличенной карточки аккаунта уже нет ({@code ClientQueries.byAccountId} её не
 * находит), проверка {@code erased} — на случай, если это когда-нибудь изменится.
 */
@Component
class ActiveAccounts {

    private final ClientQueries clients;

    ActiveAccounts(ClientQueries clients) {
        this.clients = clients;
    }

    boolean isActive(UUID accountId) {
        return clients.byAccountId(accountId)
                .map(card -> !card.blocked() && !card.erased())
                .orElse(true);
    }
}
