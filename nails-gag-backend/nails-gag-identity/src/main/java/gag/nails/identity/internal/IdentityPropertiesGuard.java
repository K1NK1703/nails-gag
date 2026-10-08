package gag.nails.identity.internal;

import gag.nails.common.error.InternalException;
import gag.nails.identity.internal.IdentityProperties.ProviderSettings;
import gag.nails.identity.model.Provider;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Проверка формы настроек при старте: ошибку в конфигурации лучше увидеть сразу, чем на первом входе.
 * Адрес возврата провайдера (если задан) — абсолютный {@code https}; {@code http} допустим только для
 * {@code localhost}. Каждый элемент {@code bootstrap-developers} — {@code PROVIDER:subject} с именем из
 * {@link Provider}. Нарушение — {@link InternalException}, приложение не стартует.
 */
@Component
class IdentityPropertiesGuard implements InitializingBean {

    private final IdentityProperties properties;

    IdentityPropertiesGuard(IdentityProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        requireRedirectUri("yandex", properties.providers().yandex());
        requireRedirectUri("vk", properties.providers().vk());
        properties.bootstrapDevelopers().forEach(IdentityPropertiesGuard::requireBootstrapEntry);
    }

    private static void requireRedirectUri(String name, ProviderSettings settings) {
        var key = "nails-gag.identity.providers." + name + ".redirect-uri";
        if (settings.redirectUri().isBlank()) {
            if (!settings.clientId().isBlank()) {
                throw new InternalException(key + " is required when client-id is set");
            }
            return;
        }
        URI uri;
        try {
            uri = new URI(settings.redirectUri());
        } catch (URISyntaxException e) {
            throw new InternalException(key + " is not a valid URI", e);
        }
        if (!uri.isAbsolute() || uri.getHost() == null) {
            throw new InternalException(key + " must be an absolute URI with a host");
        }
        var https = "https".equalsIgnoreCase(uri.getScheme());
        var localHttp = "http".equalsIgnoreCase(uri.getScheme()) && "localhost".equalsIgnoreCase(uri.getHost());
        if (!https && !localHttp) {
            throw new InternalException(key + " must use https (http is allowed for localhost only)");
        }
    }

    private static void requireBootstrapEntry(String entry) {
        var key = "nails-gag.identity.bootstrap-developers";
        var colon = entry.indexOf(':');
        if (colon <= 0 || entry.substring(colon + 1).isBlank()) {
            throw new InternalException(key + " entry must look like PROVIDER:subject, got '" + entry + "'");
        }
        var provider = entry.substring(0, colon);
        if (Arrays.stream(Provider.values()).noneMatch(known -> known.name().equals(provider))) {
            throw new InternalException(key + " entry '" + entry + "' names unknown provider '" + provider + "'");
        }
    }
}
