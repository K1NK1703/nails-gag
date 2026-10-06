package gag.nails.identity.internal;

import gag.nails.clients.ClientQueries;
import gag.nails.clients.model.ClientView;
import gag.nails.identity.Provider;
import gag.nails.identity.internal.provider.ProviderProfile;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Кого узнаём при входе (спецификация 4.2), по порядку: способ входа {@code (provider, subject)};
 * подтверждённый телефон профиля среди {@code login_method.phone}; подтверждённая почта среди {@code
 * login_method.email}; телефон, затем почта среди карточек клиентов — карточка с аккаунтом ведёт в него
 * (номер мог быть внесён мастером после регистрации), карточка без аккаунта — кандидат на привязку при
 * регистрации; иначе никого. По почте ищутся только карточки без аккаунта и ровно одна — так же, как
 * выбирает карточку {@code clients.register}, чтобы сопоставление и привязка сошлись на одной карточке. Телефон сильнее почты: если они указывают на разных людей, выигрывает
 * телефон. Только доверенные данные: номер для связи, введённый клиентом, не участвует.
 */
@Component
class LoginMatcher {

    sealed interface Match permits KnownAccount, UnregisteredCard, Nobody {}

    /** Аккаунт найден; {@code method} — его способ входа с этим {@code subject}, если он уже есть. */
    record KnownAccount(UUID accountId, @Nullable LoginMethodEntity method) implements Match {}

    /** Карточка без аккаунта, к которой привяжется регистрация. */
    record UnregisteredCard(ClientView card) implements Match {}

    /** Никого: регистрация с новой карточкой. */
    record Nobody() implements Match {}

    private final LoginMethodRepository loginMethods;
    private final ClientQueries clients;

    LoginMatcher(LoginMethodRepository loginMethods, ClientQueries clients) {
        this.loginMethods = loginMethods;
        this.clients = clients;
    }

    Match match(Provider provider, ProviderProfile profile) {
        var own = loginMethods.findByProviderAndSubject(provider, profile.subject());
        if (own.isPresent()) {
            return new KnownAccount(own.get().accountId(), own.get());
        }
        var byContact = byPhone(profile.phone()).or(() -> byEmail(profile.email()));
        if (byContact.isPresent()) {
            return new KnownAccount(byContact.get().accountId(), null);
        }
        var card = cardByPhone(profile.phone()).or(() -> cardByEmail(profile.email()));
        if (card.isEmpty()) {
            return new Nobody();
        }
        var accountId = card.get().accountId();
        return accountId == null ? new UnregisteredCard(card.get()) : new KnownAccount(accountId, null);
    }

    private Optional<LoginMethodEntity> byPhone(@Nullable String phone) {
        return phone == null ? Optional.empty() : loginMethods.findFirstByPhoneOrderByLinkedAtAscIdAsc(phone);
    }

    private Optional<LoginMethodEntity> byEmail(@Nullable String email) {
        return email == null ? Optional.empty() : loginMethods.findFirstByEmailOrderByLinkedAtAscIdAsc(email);
    }

    private Optional<ClientView> cardByPhone(@Nullable String phone) {
        return phone == null ? Optional.empty() : clients.byPhone(phone);
    }

    private Optional<ClientView> cardByEmail(@Nullable String email) {
        return email == null ? Optional.empty() : clients.byEmail(email);
    }
}
