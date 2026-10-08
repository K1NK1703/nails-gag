package gag.nails.identity.internal.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.identity.error.LoginFailedException;
import gag.nails.identity.error.ProviderUnavailableException;
import gag.nails.identity.model.AuthorizationResponse;
import gag.nails.identity.model.Provider;
import gag.nails.identity.testing.FakeProfile;
import java.net.URI;
import org.junit.jupiter.api.Test;

class FakeIdentityProviderTest {

    private static final URI REDIRECT = URI.create("http://localhost:5173/auth/callback");

    private final FakeIdentityProvider provider = new FakeIdentityProvider();

    @Test
    void isAlwaysEnabledFakeProvider() {
        assertThat(provider.id()).isEqualTo(Provider.FAKE);
        assertThat(provider.enabled()).isTrue();
    }

    @Test
    void authorizationUriIsAnUrnCarryingState() {
        var uri = provider.authorizationUri("st&ate", "challenge", REDIRECT);

        assertThat(uri.getScheme()).isEqualTo("urn");
        assertThat(uri.toString()).isEqualTo("urn:nails-gag:fake?state=st%26ate");
    }

    @Test
    void codeIsSingleUseAndYieldsNormalizedProfile() {
        var code = provider.issueCode(new FakeProfile("u1", "8 903 765 94 18", "Ann@Example.COM", "Анна", null));

        var profile = provider.exchange(new AuthorizationResponse(code, "s", null), "v", REDIRECT);

        assertThat(profile).isEqualTo(new ProviderProfile("u1", "+79037659418", "ann@example.com", "Анна", null));
        assertThatThrownBy(() -> provider.exchange(new AuthorizationResponse(code, "s", null), "v", REDIRECT))
                .isInstanceOf(LoginFailedException.class);
    }

    @Test
    void codesAreDistinct() {
        var profile = new FakeProfile("u1", null, null, null, null);
        assertThat(provider.issueCode(profile)).isNotEqualTo(provider.issueCode(profile));
    }

    @Test
    void unknownCodeIsLoginFailure() {
        assertThatThrownBy(() -> provider.exchange(new AuthorizationResponse("nope", "s", null), "v", REDIRECT))
                .isInstanceOf(LoginFailedException.class);
    }

    @Test
    void failNextExchangeFailsExactlyOnceAndKeepsTheCode() {
        var code = provider.issueCode(new FakeProfile("u1", null, null, null, null));
        provider.failNextExchange();

        assertThatThrownBy(() -> provider.exchange(new AuthorizationResponse(code, "s", null), "v", REDIRECT))
                .isInstanceOf(ProviderUnavailableException.class);
        assertThat(provider.exchange(new AuthorizationResponse(code, "s", null), "v", REDIRECT)
                        .subject())
                .isEqualTo("u1");
    }
}
