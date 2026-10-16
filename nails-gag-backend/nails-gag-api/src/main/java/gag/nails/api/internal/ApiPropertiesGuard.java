package gag.nails.api.internal;

import gag.nails.common.error.InternalException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Проверка настроек HTTP-слоя при старте. {@code site-origin} — абсолютный URI без пути, запроса и данных
 * пользователя, {@code https} ({@code http} допустим только для {@code localhost}): с ним побуквенно сверяется
 * заголовок {@code Origin}. {@code login-state-key}, если задан, — base64 ровно 32 байт. При профиле {@code prod}
 * ещё нужны непустой ключ (иначе cookie этапа входа не переживают перезапуск) и {@code cookies.secure=true}.
 * Нарушение — {@link InternalException}, приложение не стартует.
 */
@Component
class ApiPropertiesGuard implements InitializingBean {

    static final String PRODUCTION_PROFILE = "prod";
    static final int LOGIN_STATE_KEY_BYTES = 32;

    private final Environment environment;
    private final ApiProperties properties;

    ApiPropertiesGuard(Environment environment, ApiProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        requireSiteOrigin(properties.siteOrigin());
        requireLoginStateKey(properties.loginStateKey());
        if (environment.matchesProfiles(PRODUCTION_PROFILE)) {
            if (properties.loginStateKey().isBlank()) {
                throw new InternalException("nails-gag.api.login-state-key is required under the prod profile");
            }
            if (!properties.cookies().secure()) {
                throw new InternalException("nails-gag.api.cookies.secure=false is forbidden under the prod profile");
            }
        }
    }

    private static void requireSiteOrigin(String siteOrigin) {
        var key = "nails-gag.api.site-origin";
        URI uri;
        try {
            uri = new URI(siteOrigin);
        } catch (URISyntaxException e) {
            throw new InternalException(key + " is not a valid URI", e);
        }
        if (!uri.isAbsolute() || uri.getHost() == null) {
            throw new InternalException(key + " must be an absolute URI with a host");
        }
        var bare = uri.getRawUserInfo() == null
                && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null;
        if (!bare) {
            throw new InternalException(key + " must be an origin: scheme, host and port only, no trailing slash");
        }
        var https = "https".equalsIgnoreCase(uri.getScheme());
        var localHttp = "http".equalsIgnoreCase(uri.getScheme()) && "localhost".equalsIgnoreCase(uri.getHost());
        if (!https && !localHttp) {
            throw new InternalException(key + " must use https (http is allowed for localhost only)");
        }
    }

    private static void requireLoginStateKey(String loginStateKey) {
        if (loginStateKey.isBlank()) {
            return;
        }
        var key = "nails-gag.api.login-state-key";
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(loginStateKey);
        } catch (IllegalArgumentException e) {
            throw new InternalException(key + " is not valid base64", e);
        }
        if (bytes.length != LOGIN_STATE_KEY_BYTES) {
            throw new InternalException(key + " must decode to exactly " + LOGIN_STATE_KEY_BYTES + " bytes");
        }
    }
}
