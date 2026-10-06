package gag.nails.identity.internal.provider;

import gag.nails.identity.AuthorizationResponse;
import gag.nails.identity.Provider;
import gag.nails.identity.error.LoginFailedException;
import gag.nails.identity.error.ProviderUnavailableException;
import gag.nails.identity.testing.FakeProfile;
import gag.nails.identity.testing.FakeProvider;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

/**
 * Подставной провайдер для тестов и локальной разработки: профили лежат в памяти по одноразовому
 * коду, сети нет. Бин создаётся только при {@code nails-gag.identity.fake-provider.enabled=true};
 * {@link FakeProviderGuard} не даёт включить его в бою.
 */
@Component
@ConditionalOnProperty(name = "nails-gag.identity.fake-provider.enabled", havingValue = "true")
class FakeIdentityProvider implements IdentityProvider, FakeProvider {

    static final String AUTHORIZE = "urn:nails-gag:fake";

    private final SecureRandom random = new SecureRandom();
    private final Map<String, FakeProfile> codes = new ConcurrentHashMap<>();
    private final AtomicBoolean failNextExchange = new AtomicBoolean();

    @Override
    public Provider id() {
        return Provider.FAKE;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public URI authorizationUri(String state, String codeChallenge, URI redirectUri) {
        return URI.create(AUTHORIZE + "?state=" + UriUtils.encodeQueryParam(state, "UTF-8"));
    }

    @Override
    public String issueCode(FakeProfile profile) {
        var bytes = new byte[16];
        random.nextBytes(bytes);
        var code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        codes.put(code, profile);
        return code;
    }

    @Override
    public void failNextExchange() {
        failNextExchange.set(true);
    }

    @Override
    public ProviderProfile exchange(AuthorizationResponse response, String codeVerifier, URI redirectUri) {
        if (failNextExchange.getAndSet(false)) {
            throw new ProviderUnavailableException();
        }
        var profile = codes.remove(response.code());
        if (profile == null) {
            throw new LoginFailedException();
        }
        return ProviderProfile.normalized(
                profile.subject(), profile.phone(), profile.email(), profile.displayName(), profile.avatarUrl());
    }
}
