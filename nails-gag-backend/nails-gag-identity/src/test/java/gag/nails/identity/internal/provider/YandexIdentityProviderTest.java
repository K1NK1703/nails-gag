package gag.nails.identity.internal.provider;

import static gag.nails.identity.internal.provider.ProviderTestSupport.CONFIGURED;
import static gag.nails.identity.internal.provider.ProviderTestSupport.DISABLED;
import static gag.nails.identity.internal.provider.ProviderTestSupport.REDIRECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import gag.nails.identity.AuthorizationResponse;
import gag.nails.identity.Provider;
import gag.nails.identity.error.LoginFailedException;
import gag.nails.identity.error.ProviderUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class YandexIdentityProviderTest {

    private static final String TOKEN_OK = """
            {"token_type": "bearer", "access_token": "y0_secret", "expires_in": 31536000, "refresh_token": "r"}
            """;

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(builder).build();
    private final YandexIdentityProvider provider =
            new YandexIdentityProvider(ProviderTestSupport.properties(CONFIGURED, DISABLED), builder.build());

    private final AuthorizationResponse response = new AuthorizationResponse("the-code", "the-state", null);

    @Test
    void identityAndEnablement() {
        assertThat(provider.id()).isEqualTo(Provider.YANDEX);
        assertThat(provider.enabled()).isTrue();
        var disabled = new YandexIdentityProvider(ProviderTestSupport.properties(DISABLED, DISABLED), builder.build());
        assertThat(disabled.enabled()).isFalse();
    }

    @Test
    void authorizationUriCarriesStateChallengeRedirectAndScopes() {
        var uri = provider.authorizationUri("st ate", "chal+lenge", REDIRECT);

        assertThat(uri.toString()).startsWith(YandexIdentityProvider.AUTHORIZE + "?");
        // значения кодируются целиком, иначе провайдер прочитает redirect_uri не так, как мы
        assertThat(uri.getRawQuery()).contains("redirect_uri=https%3A%2F%2Fnails.gag%2Fauth%2Fcallback%3Fx%3D1");
        assertThat(ProviderTestSupport.query(uri))
                .containsEntry("response_type", "code")
                .containsEntry("client_id", "app-id")
                .containsEntry("redirect_uri", REDIRECT.toString())
                .containsEntry("state", "st ate")
                .containsEntry("code_challenge", "chal+lenge")
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("scope", "login:info login:email login:default_phone login:avatar");
    }

    @Test
    void exchangeReadsEveryFieldAndNormalizesPhoneAndEmail() {
        expectTokenExchange().andRespond(withSuccess(TOKEN_OK, MediaType.APPLICATION_JSON));
        expectInfo().andRespond(withSuccess("""
                        {
                          "id": "1000034426",
                          "login": "ivan",
                          "client_id": "app-id",
                          "psuid": "1.AAceCw",
                          "default_email": "Ivan.Petrov@Yandex.RU",
                          "emails": ["Ivan.Petrov@Yandex.RU"],
                          "default_phone": {"id": 12345678, "number": "8 (903) 765-94-18"},
                          "display_name": "ivan",
                          "real_name": "Иван Иванов",
                          "first_name": "Иван",
                          "last_name": "Иванов",
                          "sex": "male",
                          "birthday": "1987-03-12",
                          "default_avatar_id": "131652443",
                          "is_avatar_empty": false
                        }
                        """, MediaType.APPLICATION_JSON));

        var profile = provider.exchange(response, "verifier", REDIRECT);

        assertThat(profile)
                .isEqualTo(new ProviderProfile(
                        "1000034426",
                        "+79037659418",
                        "ivan.petrov@yandex.ru",
                        "ivan",
                        "https://avatars.yandex.net/get-yapic/131652443/islands-200"));
        server.verify();
    }

    @Test
    void missingFieldsBecomeNullAndRealNameBacksUpDisplayName() {
        expectTokenExchange().andRespond(withSuccess(TOKEN_OK, MediaType.APPLICATION_JSON));
        expectInfo().andRespond(withSuccess("""
                        {"id": "42", "login": "x", "client_id": "app-id", "psuid": "p",
                         "real_name": "Иван Иванов", "default_email": "  ", "default_avatar_id": "0/0-0", "is_avatar_empty": true}
                        """, MediaType.APPLICATION_JSON));

        var profile = provider.exchange(response, "verifier", REDIRECT);

        assertThat(profile).isEqualTo(new ProviderProfile("42", null, null, "Иван Иванов", null));
    }

    @Test
    void phoneInE164IsKeptAndUnreadablePhoneIsDropped() {
        expectTokenExchange().andRespond(withSuccess(TOKEN_OK, MediaType.APPLICATION_JSON));
        expectInfo().andRespond(withSuccess("""
                        {"id": "42", "default_phone": {"id": 1, "number": "+79037659418"}}
                        """, MediaType.APPLICATION_JSON));
        assertThat(provider.exchange(response, "verifier", REDIRECT).phone()).isEqualTo("+79037659418");
        server.reset();

        expectTokenExchange().andRespond(withSuccess(TOKEN_OK, MediaType.APPLICATION_JSON));
        expectInfo().andRespond(withSuccess("""
                        {"id": "42", "default_phone": {"id": 1, "number": "+7 *** *** ** 18"}}
                        """, MediaType.APPLICATION_JSON));
        assertThat(provider.exchange(response, "verifier", REDIRECT).phone()).isNull();
    }

    @Test
    void rejectedCodeIsLoginFailure() {
        expectTokenExchange()
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\": \"invalid_grant\", \"error_description\": \"Code has expired\"}"));

        assertThatThrownBy(() -> provider.exchange(response, "verifier", REDIRECT))
                .isInstanceOf(LoginFailedException.class);
        server.verify();
    }

    @Test
    void serverErrorOnExchangeIsUnavailability() {
        expectTokenExchange().andRespond(withServerError());

        assertThatThrownBy(() -> provider.exchange(response, "verifier", REDIRECT))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void anyErrorOnProfileRequestIsUnavailability() {
        expectTokenExchange().andRespond(withSuccess(TOKEN_OK, MediaType.APPLICATION_JSON));
        expectInfo().andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> provider.exchange(response, "verifier", REDIRECT))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void answerWithoutTokenOrIdIsUnavailability() {
        expectTokenExchange().andRespond(withSuccess("{\"token_type\": \"bearer\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.exchange(response, "verifier", REDIRECT))
                .isInstanceOf(ProviderUnavailableException.class);
        server.reset();

        expectTokenExchange().andRespond(withSuccess(TOKEN_OK, MediaType.APPLICATION_JSON));
        expectInfo().andRespond(withSuccess("{\"login\": \"ivan\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.exchange(response, "verifier", REDIRECT))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void unreadableAnswerIsUnavailabilityWithCause() {
        expectTokenExchange().andRespond(withSuccess("<html>oops</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> provider.exchange(response, "verifier", REDIRECT))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasCauseInstanceOf(RestClientException.class);
    }

    private ResponseActions expectTokenExchange() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("code", "the-code");
        form.add("client_id", "app-id");
        form.add("client_secret", "app-secret");
        form.add("code_verifier", "verifier");
        return server.expect(requestTo(YandexIdentityProvider.TOKEN))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(form));
    }

    private ResponseActions expectInfo() {
        return server.expect(requestTo(YandexIdentityProvider.INFO))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "OAuth y0_secret"));
    }
}
