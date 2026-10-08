package gag.nails.identity.internal.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import gag.nails.identity.error.LoginFailedException;
import gag.nails.identity.error.ProviderUnavailableException;
import gag.nails.identity.internal.IdentityProperties;
import gag.nails.identity.internal.IdentityProperties.ProviderSettings;
import gag.nails.identity.model.AuthorizationResponse;
import gag.nails.identity.model.Provider;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Вход через VK ID (OAuth 2.1 с PKCE).
 *
 * <p>Сверено с документацией 2026-10-06: <a
 * href="https://id.vk.ru/about/business/go/docs/ru/vkid/latest/vk-id/connection/api-integration/api-description">Описание
 * API VK ID</a>.
 *
 * <ul>
 *   <li>Авторизация {@code GET https://id.vk.ru/authorize}: обязательны {@code response_type=code},
 *       {@code client_id}, {@code redirect_uri}, {@code state} (не короче 32 символов), {@code
 *       code_challenge} = {@code BASE64URL(SHA256(code_verifier))}, {@code code_challenge_method=S256};
 *       {@code scope} через пробел, без него — только {@code vkid.personal_info} (имя, фото). Права
 *       {@code phone} и {@code email} дают телефон и почту в профиле.
 *   <li>Возврат: {@code code} (живёт 10 минут), {@code device_id}, {@code state}. Без {@code
 *       device_id} обмен невозможен — вход считается неудавшимся.
 *   <li>Обмен {@code POST https://id.vk.ru/oauth2/auth}, {@code application/x-www-form-urlencoded}:
 *       {@code grant_type=authorization_code}, {@code code_verifier} (43–128 символов из {@code
 *       a-z A-Z 0-9 _ -}), {@code redirect_uri}, {@code code}, {@code client_id}, {@code device_id},
 *       {@code state}; для конфиденциального приложения ещё {@code service_token} — сюда идёт {@code
 *       client-secret} из настроек, если он задан. Ответ: {@code access_token}, {@code refresh_token},
 *       {@code id_token}, {@code user_id}, {@code state}, {@code scope}. Ошибка: {@code error} и {@code
 *       error_description}; код статуса в документе не указан, поэтому ошибка распознаётся и по телу
 *       при {@code 200}: {@code server_error} и {@code temporarily_unavailable} — недоступность,
 *       остальное — отказ.
 *   <li>Профиль {@code POST https://id.vk.ru/oauth2/user_info}: {@code client_id}, {@code access_token}
 *       в теле; ответ {@code user: {user_id, first_name, last_name, phone, avatar, email, sex, verified,
 *       birthday}}. Форматы {@code user_id} (число или строка) и {@code phone} в документе не показаны:
 *       {@code user_id} читается как строка в обоих случаях, телефон нормализуется в E.164, а
 *       нечитаемый (например, скрытый звёздочками) отбрасывается.
 * </ul>
 *
 * <p>Токены живут только внутри {@link #exchange}.
 */
@Component
class VkIdentityProvider implements IdentityProvider {

    static final String AUTHORIZE = "https://id.vk.ru/authorize";
    static final String TOKEN = "https://id.vk.ru/oauth2/auth";
    static final String USER_INFO = "https://id.vk.ru/oauth2/user_info";
    static final String SCOPE = "phone email";

    private static final Logger log = LoggerFactory.getLogger(VkIdentityProvider.class);
    private static final Set<String> UNAVAILABLE_ERRORS = Set.of("server_error", "temporarily_unavailable");

    private final ProviderSettings settings;
    private final RestClient http;

    VkIdentityProvider(IdentityProperties properties, RestClient http) {
        this.settings = properties.providers().vk();
        this.http = http;
    }

    @Override
    public Provider id() {
        return Provider.VK;
    }

    @Override
    public boolean enabled() {
        return settings.enabled();
    }

    @Override
    public URI authorizationUri(String state, String codeChallenge, URI redirectUri) {
        var params = new LinkedHashMap<String, String>();
        params.put("response_type", "code");
        params.put("client_id", settings.clientId());
        params.put("redirect_uri", redirectUri.toString());
        params.put("state", state);
        params.put("code_challenge", codeChallenge);
        params.put("code_challenge_method", "S256");
        params.put("scope", SCOPE);
        return ProviderHttp.authorizationUri(AUTHORIZE, params);
    }

    @Override
    public ProviderProfile exchange(AuthorizationResponse response, String codeVerifier, URI redirectUri) {
        if (response.deviceId() == null || response.deviceId().isBlank()) {
            log.warn("vk token: authorization response without device_id");
            throw new LoginFailedException();
        }
        var token = exchangeCode(response, codeVerifier, redirectUri);
        var user = fetchUser(token.accessToken());
        var subject = firstNonBlank(user.userId(), token.userId());
        if (subject == null) {
            log.warn("vk user_info: answer without user_id");
            throw new ProviderUnavailableException();
        }
        var displayName = Stream.of(user.firstName(), user.lastName())
                .filter(part -> part != null && !part.isBlank())
                .map(String::strip)
                .collect(Collectors.joining(" "));
        return ProviderProfile.normalized(subject, user.phone(), user.email(), displayName, user.avatar());
    }

    private TokenResponse exchangeCode(AuthorizationResponse response, String codeVerifier, URI redirectUri) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("code_verifier", codeVerifier);
        form.add("redirect_uri", redirectUri.toString());
        form.add("code", response.code());
        form.add("client_id", settings.clientId());
        form.add("device_id", response.deviceId());
        form.add("state", response.state());
        if (!settings.clientSecret().isBlank()) {
            form.add("service_token", settings.clientSecret());
        }
        var token = ProviderHttp.call(
                log,
                "vk token",
                () -> http.post()
                        .uri(TOKEN)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, ProviderHttp.codeRejectedOn4xx(log, "vk token"))
                        .body(TokenResponse.class));
        if (token.error() != null) {
            log.warn("vk token: provider answered with error {}", token.error());
            if (UNAVAILABLE_ERRORS.contains(token.error())) {
                throw new ProviderUnavailableException();
            }
            throw new LoginFailedException();
        }
        if (token.accessToken() == null || token.accessToken().isBlank()) {
            log.warn("vk token: answer without access_token");
            throw new ProviderUnavailableException();
        }
        return token;
    }

    private User fetchUser(String accessToken) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", settings.clientId());
        form.add("access_token", accessToken);
        var info = ProviderHttp.call(
                log,
                "vk user_info",
                () -> http.post()
                        .uri(USER_INFO)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, ProviderHttp.unavailableOnError(log, "vk user_info"))
                        .body(UserInfoResponse.class));
        if (info.user() == null) {
            log.warn("vk user_info: answer without user{}", info.error() == null ? "" : ", error " + info.error());
            throw new ProviderUnavailableException();
        }
        return info.user();
    }

    private static @Nullable String firstNonBlank(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(
            @JsonProperty("access_token") @Nullable String accessToken,
            @JsonProperty("user_id") @Nullable String userId,
            @Nullable String error) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UserInfoResponse(@Nullable User user, @Nullable String error) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record User(
            @JsonProperty("user_id") @Nullable String userId,
            @JsonProperty("first_name") @Nullable String firstName,
            @JsonProperty("last_name") @Nullable String lastName,
            @Nullable String phone,
            @Nullable String email,
            @Nullable String avatar) {}
}
