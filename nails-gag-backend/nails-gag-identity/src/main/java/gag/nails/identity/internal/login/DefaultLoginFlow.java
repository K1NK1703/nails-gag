package gag.nails.identity.internal.login;

import gag.nails.common.actor.Actor;
import gag.nails.identity.LoginFlow;
import gag.nails.identity.command.CompleteRegistration;
import gag.nails.identity.error.LoginFailedException;
import gag.nails.identity.internal.IdentityProperties;
import gag.nails.identity.internal.provider.IdentityProviders;
import gag.nails.identity.internal.provider.ProviderProfile;
import gag.nails.identity.internal.session.OpaqueTokens;
import gag.nails.identity.model.AuthorizationResponse;
import gag.nails.identity.model.ClientInfo;
import gag.nails.identity.model.Linked;
import gag.nails.identity.model.LoginPurpose;
import gag.nails.identity.model.LoginResult;
import gag.nails.identity.model.LoginStart;
import gag.nails.identity.model.LoginState;
import gag.nails.identity.model.Provider;
import gag.nails.identity.model.RegistrationPrefill;
import gag.nails.identity.model.SignedIn;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Вход, регистрация и привязка (спецификация 4.2). Класс намеренно не транзакционный: проверка {@code
 * state} и обмен кода у провайдера идут без соединения с базой — аноним с валидным {@code state} не
 * должен держать соединение из пула, пока провайдер отвечает. Вся запись — короткие транзакции
 * {@link LoginWriter}. HTTP-слой обязан вызывать {@code LoginFlow} без внешней транзакции, иначе это
 * разделение теряется.
 *
 * <p>После открытия сессии карточка перечитывается в отдельной транзакции: вход, совпавший по времени с
 * блокировкой карточки, не оставляет заблокированному живой сессии ({@link LoginWriter#rejectIfBlocked}).
 */
@Service
class DefaultLoginFlow implements LoginFlow {

    static final Duration STATE_LIFETIME = Duration.ofMinutes(10);

    /** У подставного провайдера нет настроек адреса возврата: он никуда не ходит. */
    static final URI FAKE_REDIRECT_URI = URI.create("urn:nails-gag:fake:callback");

    private final IdentityProviders providers;
    private final IdentityProperties properties;
    private final LoginWriter writer;
    private final Clock clock;

    DefaultLoginFlow(IdentityProviders providers, IdentityProperties properties, LoginWriter writer, Clock clock) {
        this.providers = providers;
        this.properties = properties;
        this.writer = writer;
        this.clock = clock;
    }

    @Override
    public LoginStart start(Provider provider, LoginPurpose purpose, @Nullable Actor actor) {
        var identityProvider = providers.get(provider);
        UUID accountId = null;
        if (purpose == LoginPurpose.LINK) {
            accountId = LoginWriter.requireAccount(actor);
        }
        var state = OpaqueTokens.random(OpaqueTokens.TOKEN_BYTES);
        var codeVerifier = OpaqueTokens.random(OpaqueTokens.VERIFIER_BYTES);
        var uri = identityProvider.authorizationUri(
                state, OpaqueTokens.codeChallenge(codeVerifier), redirectUri(provider));
        return new LoginStart(uri, new LoginState(state, codeVerifier, purpose, accountId, clock.instant()));
    }

    @Override
    public LoginResult complete(
            Provider provider, AuthorizationResponse response, LoginState state, ClientInfo client) {
        var now = clock.instant();
        if (!OpaqueTokens.equal(state.state(), response.state())
                || state.issuedAt().plus(STATE_LIFETIME).isBefore(now)) {
            writer.journalFailure(provider, client);
            throw new LoginFailedException();
        }
        var identityProvider = providers.get(provider);
        ProviderProfile profile;
        try {
            profile = identityProvider.exchange(response, state.codeVerifier(), redirectUri(provider));
        } catch (LoginFailedException e) {
            writer.journalFailure(provider, client);
            throw e;
        }
        var result = writer.complete(provider, profile, state, client);
        if (result instanceof SignedIn signedIn) {
            writer.rejectIfBlocked(signedIn, provider, client);
        }
        return result;
    }

    @Override
    public Optional<RegistrationPrefill> registrationPrefill(String ticket) {
        return writer.registrationPrefill(ticket);
    }

    @Override
    public SignedIn completeRegistration(String ticket, CompleteRegistration command, ClientInfo client) {
        var opened = writer.completeRegistration(ticket, command, client);
        writer.rejectIfBlocked(opened.signedIn(), opened.provider(), client);
        return opened.signedIn();
    }

    @Override
    public Linked confirmLink(String ticket, Actor actor, ClientInfo client) {
        return writer.confirmLink(ticket, actor, client);
    }

    private URI redirectUri(Provider provider) {
        return switch (provider) {
            case YANDEX -> URI.create(properties.providers().yandex().redirectUri());
            case VK -> URI.create(properties.providers().vk().redirectUri());
            case FAKE -> FAKE_REDIRECT_URI;
        };
    }
}
