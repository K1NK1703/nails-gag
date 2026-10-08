package gag.nails.identity.internal.provider;

import static gag.nails.identity.internal.provider.ProviderTestSupport.CONFIGURED;
import static gag.nails.identity.internal.provider.ProviderTestSupport.DISABLED;
import static gag.nails.identity.internal.provider.ProviderTestSupport.REDIRECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import gag.nails.identity.error.LoginFailedException;
import gag.nails.identity.error.ProviderUnavailableException;
import gag.nails.identity.internal.IdentityProperties.ProviderSettings;
import gag.nails.identity.model.AuthorizationResponse;
import gag.nails.identity.model.Provider;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

class VkIdentityProviderTest {

    private static final String TOKEN_OK = """
            {"refresh_token": "r", "access_token": "vk1.a.secret", "id_token": "i", "token_type": "Bearer",
             "expires_in": 3600, "user_id": 123456, "state": "the-state", "scope": "phone email"}
            """;

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(builder).build();
    private final VkIdentityProvider provider =
            new VkIdentityProvider(ProviderTestSupport.properties(DISABLED, CONFIGURED), builder.build());

    private final AuthorizationResponse response = new AuthorizationResponse("the-code", "the-state", "dev-1");

    @Test
    void identityAndEnablement() {
        assertThat(provider.id()).isEqualTo(Provider.VK);
        assertThat(provider.enabled()).isTrue();
        var disabled = new VkIdentityProvider(ProviderTestSupport.properties(DISABLED, DISABLED), builder.build());
        assertThat(disabled.enabled()).isFalse();
    }

    @Test
    void authorizationUriCarriesStateChallengeRedirectAndScopes() {
        var uri = provider.authorizationUri("st ate", "chal+lenge", REDIRECT);

        assertThat(uri.toString()).startsWith(VkIdentityProvider.AUTHORIZE + "?");
        assertThat(uri.getRawQuery()).contains("redirect_uri=https%3A%2F%2Fnails.gag%2Fauth%2Fcallback%3Fx%3D1");
        assertThat(ProviderTestSupport.query(uri))
                .containsEntry("response_type", "code")
                .containsEntry("client_id", "app-id")
                .containsEntry("redirect_uri", REDIRECT.toString())
                .containsEntry("state", "st ate")
                .containsEntry("code_challenge", "chal+lenge")
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("scope", "phone email");
    }

    @Test
    void exchangeReadsEveryFieldAndNormalizesPhoneAndEmail() {
        expectTokenExchange(true).andRespond(withSuccess(TOKEN_OK, MediaType.APPLICATION_JSON));
        expectUserInfo().andRespond(withSuccess("""
                        {"user": {"user_id": "123456", "first_name": "Пётр", "last_name": "Сидоров",
                                  "phone": "79037659418", "avatar": "https://sun1.userapi.com/s/v1/ig2/abc.jpg",
                                  "email": "Petr.Sidorov@MAIL.ru", "sex": 2, "verified": false, "birthday": "12.03.1987"}}
                        """, MediaType.APPLICATION_JSON));

        var profile = provider.exchange(response, "verifier", REDIRECT);

        assertThat(profile)
                .isEqualTo(new ProviderProfile(
                        "123456",
                        "+79037659418",
                        "petr.sidorov@mail.ru",
                        "Пётр Сидоров",
                        "https://sun1.userapi.com/s/v1/ig2/abc.jpg"));
        server.verify();
    }

    @Test
    void missingFieldsBecomeNullAndNumericUserIdFromTokenIsTheFallbackSubject() {
        expectTokenExchange(true).andRespond(withSuccess(TOKEN_OK, MediaType.APPLICATION_JSON));
        expectUserInfo().andRespond(withSuccess("""
                        {"user": {"first_name": "Пётр", "email": ""}}
                        """, MediaType.APPLICATION_JSON));

        var profile = provider.exchange(response, "verifier", REDIRECT);

        assertThat(profile).isEqualTo(new ProviderProfile("123456", null, null, "Пётр", null));
    }

    @Test
    void phoneSpellingsBecomeE164AndMaskedPhoneIsDropped() {
        for (var spelling : new String[] {"+7 903 765-94-18", "8 (903) 765 94 18", "9037659418"}) {
            server.reset();
            expectTokenExchange(true).andRespond(withSuccess(TOKEN_OK, MediaType.APPLICATION_JSON));
            expectUserInfo()
                    .andRespond(withSuccess(
                            "{\"user\": {\"user_id\": 7, \"phone\": \"" + spelling + "\"}}",
                            MediaType.APPLICATION_JSON));
            assertThat(provider.exchange(response, "verifier", REDIRECT).phone())
                    .as(spelling)
                    .isEqualTo("+79037659418");
        }
        server.reset();
        expectTokenExchange(true).andRespond(withSuccess(TOKEN_OK, MediaType.APPLICATION_JSON));
        expectUserInfo()
                .andRespond(withSuccess(
                        "{\"user\": {\"user_id\": 7, \"phone\": \"+7 *** *** ** 18\"}}", MediaType.APPLICATION_JSON));
        var profile = provider.exchange(response, "verifier", REDIRECT);
        assertThat(profile.subject()).isEqualTo("7");
        assertThat(profile.phone()).isNull();
    }

    @Test
    void publicApplicationSendsNoServiceToken() {
        var publicApp = new ProviderSettings("app-id", "", REDIRECT.toString());
        var provider = new VkIdentityProvider(ProviderTestSupport.properties(DISABLED, publicApp), builder.build());
        expectTokenExchange(false).andRespond(withSuccess(TOKEN_OK, MediaType.APPLICATION_JSON));
        expectUserInfo().andRespond(withSuccess("{\"user\": {\"user_id\": \"1\"}}", MediaType.APPLICATION_JSON));

        assertThat(provider.exchange(response, "verifier", REDIRECT).subject()).isEqualTo("1");
        server.verify();
    }

    @Test
    void missingDeviceIdIsLoginFailureWithoutAnyRequest() {
        assertThatThrownBy(() -> provider.exchange(
                        new AuthorizationResponse("the-code", "the-state", null), "verifier", REDIRECT))
                .isInstanceOf(LoginFailedException.class);
        assertThatThrownBy(() -> provider.exchange(
                        new AuthorizationResponse("the-code", "the-state", " "), "verifier", REDIRECT))
                .isInstanceOf(LoginFailedException.class);
        server.verify();
    }

    @Test
    void rejectedCodeIsLoginFailure() {
        expectTokenExchange(true)
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\": \"invalid_request\", \"error_description\": \"bad code\"}"));

        assertThatThrownBy(() -> provider.exchange(response, "verifier", REDIRECT))
                .isInstanceOf(LoginFailedException.class);
    }

    @Test
    void errorBodyWithOkStatusIsReadToo() {
        expectTokenExchange(true)
                .andRespond(withSuccess(
                        "{\"error\": \"access_denied\", \"error_description\": \"user refused\"}",
                        MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.exchange(response, "verifier", REDIRECT))
                .isInstanceOf(LoginFailedException.class);
        server.reset();

        expectTokenExchange(true)
                .andRespond(withSuccess(
                        "{\"error\": \"temporarily_unavailable\", \"error_description\": \"\"}",
                        MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.exchange(response, "verifier", REDIRECT))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void serverErrorOnExchangeIsUnavailability() {
        expectTokenExchange(true).andRespond(withServerError());

        assertThatThrownBy(() -> provider.exchange(response, "verifier", REDIRECT))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void anyErrorOrEmptyUserOnProfileRequestIsUnavailability() {
        expectTokenExchange(true).andRespond(withSuccess(TOKEN_OK, MediaType.APPLICATION_JSON));
        expectUserInfo().andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> provider.exchange(response, "verifier", REDIRECT))
                .isInstanceOf(ProviderUnavailableException.class);
        server.reset();

        expectTokenExchange(true).andRespond(withSuccess(TOKEN_OK, MediaType.APPLICATION_JSON));
        expectUserInfo()
                .andRespond(withSuccess(
                        "{\"error\": \"invalid_token\", \"error_description\": \"\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.exchange(response, "verifier", REDIRECT))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    private ResponseActions expectTokenExchange(boolean confidential) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("code_verifier", "verifier");
        form.add("redirect_uri", REDIRECT.toString());
        form.add("code", "the-code");
        form.add("client_id", "app-id");
        form.add("device_id", "dev-1");
        form.add("state", "the-state");
        if (confidential) {
            form.add("service_token", "app-secret");
        }
        return server.expect(requestTo(VkIdentityProvider.TOKEN))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(form));
    }

    private ResponseActions expectUserInfo() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", "app-id");
        form.add("access_token", "vk1.a.secret");
        return server.expect(requestTo(VkIdentityProvider.USER_INFO))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(form));
    }
}
