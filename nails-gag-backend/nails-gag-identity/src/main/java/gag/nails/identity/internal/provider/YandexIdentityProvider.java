package gag.nails.identity.internal.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import gag.nails.identity.AuthorizationResponse;
import gag.nails.identity.Provider;
import gag.nails.identity.error.ProviderUnavailableException;
import gag.nails.identity.internal.IdentityProperties;
import gag.nails.identity.internal.IdentityProperties.ProviderSettings;
import java.net.URI;
import java.util.LinkedHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Вход через Яндекс ID (OAuth 2.0 с PKCE).
 *
 * <p>Сверено с документацией 2026-10-06:
 *
 * <ul>
 *   <li><a href="https://yandex.ru/dev/id/doc/ru/codes/code-url">Получение кода и обмен на токен</a>:
 *       авторизация {@code GET https://oauth.yandex.ru/authorize} с {@code response_type=code},
 *       {@code client_id}, {@code redirect_uri}, {@code scope} (через пробел), {@code state} (до 1024
 *       символов), {@code code_challenge}, {@code code_challenge_method=S256}; обмен {@code POST
 *       https://oauth.yandex.ru/token}, {@code application/x-www-form-urlencoded}: {@code
 *       grant_type=authorization_code}, {@code code}, {@code client_id}, {@code client_secret}, {@code
 *       code_verifier}; ответ — {@code access_token}, {@code token_type}, {@code expires_in}; ошибка —
 *       {@code error} ({@code invalid_grant}, {@code bad_verification_code}, …). Статус ошибки в
 *       документе не указан; по RFC 6749 это {@code 400} — здесь любой {@code 4xx} означает «код
 *       отвергнут».
 *   <li><a href="https://yandex.ru/dev/id/doc/ru/user-information">Информация о пользователе</a>: {@code
 *       GET https://login.yandex.ru/info?format=json}, заголовок {@code Authorization: OAuth
 *       <token>}; поля {@code id}, {@code default_email}, {@code default_phone.number} (вида {@code
 *       +79037659418}), {@code display_name}, {@code real_name}, {@code default_avatar_id}, {@code
 *       is_avatar_empty}; аватар {@code https://avatars.yandex.net/get-yapic/<id>/<size>}, размер {@code
 *       islands-200} — 200×200.
 * </ul>
 *
 * <p>Имена прав {@code login:info}, {@code login:email}, {@code login:default_phone}, {@code
 * login:avatar} на страницах документации в явном виде не перечислены (там права описаны словами);
 * они сверены по нескольким сторонним интеграциям и совпадают с ориентиром плана — считать «сверено
 * косвенно».
 *
 * <p>Телефон и почта приходят из профиля Яндекса и считаются подтверждёнными им. Токен живёт только
 * внутри {@link #exchange}.
 */
@Component
class YandexIdentityProvider implements IdentityProvider {

    static final String AUTHORIZE = "https://oauth.yandex.ru/authorize";
    static final String TOKEN = "https://oauth.yandex.ru/token";
    static final String INFO = "https://login.yandex.ru/info?format=json";
    static final String SCOPE = "login:info login:email login:default_phone login:avatar";
    static final String AVATAR_SIZE = "islands-200";

    private static final Logger log = LoggerFactory.getLogger(YandexIdentityProvider.class);

    private final ProviderSettings settings;
    private final RestClient http;

    YandexIdentityProvider(IdentityProperties properties, RestClient http) {
        this.settings = properties.providers().yandex();
        this.http = http;
    }

    @Override
    public Provider id() {
        return Provider.YANDEX;
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
        params.put("scope", SCOPE);
        params.put("state", state);
        params.put("code_challenge", codeChallenge);
        params.put("code_challenge_method", "S256");
        return ProviderHttp.authorizationUri(AUTHORIZE, params);
    }

    @Override
    public ProviderProfile exchange(AuthorizationResponse response, String codeVerifier, URI redirectUri) {
        var token = exchangeCode(response.code(), codeVerifier);
        var info = fetchInfo(token);
        return toProfile(info);
    }

    private String exchangeCode(String code, String codeVerifier) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("client_id", settings.clientId());
        form.add("client_secret", settings.clientSecret());
        form.add("code_verifier", codeVerifier);
        var token = ProviderHttp.call(
                log,
                "yandex token",
                () -> http.post()
                        .uri(TOKEN)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, ProviderHttp.codeRejectedOn4xx(log, "yandex token"))
                        .body(TokenResponse.class));
        if (token.accessToken() == null || token.accessToken().isBlank()) {
            log.warn("yandex token: answer without access_token");
            throw new ProviderUnavailableException();
        }
        return token.accessToken();
    }

    private InfoResponse fetchInfo(String accessToken) {
        return ProviderHttp.call(
                log,
                "yandex info",
                () -> http.get()
                        .uri(INFO)
                        .header(HttpHeaders.AUTHORIZATION, "OAuth " + accessToken)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, ProviderHttp.unavailableOnError(log, "yandex info"))
                        .body(InfoResponse.class));
    }

    private static ProviderProfile toProfile(InfoResponse info) {
        if (info.id() == null || info.id().isBlank()) {
            log.warn("yandex info: answer without id");
            throw new ProviderUnavailableException();
        }
        var displayName =
                info.displayName() == null || info.displayName().isBlank() ? info.realName() : info.displayName();
        var avatar = Boolean.FALSE.equals(info.isAvatarEmpty())
                        && info.defaultAvatarId() != null
                        && !info.defaultAvatarId().isBlank()
                ? "https://avatars.yandex.net/get-yapic/" + info.defaultAvatarId() + "/" + AVATAR_SIZE
                : null;
        var phone = info.defaultPhone() == null ? null : info.defaultPhone().number();
        return ProviderProfile.normalized(info.id(), phone, info.defaultEmail(), displayName, avatar);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(
            @JsonProperty("access_token") @Nullable String accessToken) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InfoResponse(
            @Nullable String id,
            @JsonProperty("default_email") @Nullable String defaultEmail,
            @JsonProperty("default_phone") @Nullable DefaultPhone defaultPhone,
            @JsonProperty("display_name") @Nullable String displayName,
            @JsonProperty("real_name") @Nullable String realName,
            @JsonProperty("default_avatar_id") @Nullable String defaultAvatarId,
            @JsonProperty("is_avatar_empty") @Nullable Boolean isAvatarEmpty) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DefaultPhone(@Nullable String number) {}
}
