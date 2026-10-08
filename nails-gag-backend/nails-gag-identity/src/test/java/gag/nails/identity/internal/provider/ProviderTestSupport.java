package gag.nails.identity.internal.provider;

import gag.nails.identity.internal.IdentityProperties;
import gag.nails.identity.internal.IdentityProperties.FakeProviderSettings;
import gag.nails.identity.internal.IdentityProperties.Jobs;
import gag.nails.identity.internal.IdentityProperties.ProviderSettings;
import gag.nails.identity.internal.IdentityProperties.Providers;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/** Настройки и разбор адресов для юнит-тестов провайдеров: без Spring-контекста и базы. */
final class ProviderTestSupport {

    static final URI REDIRECT = URI.create("https://nails.gag/auth/callback?x=1");
    static final ProviderSettings CONFIGURED = new ProviderSettings("app-id", "app-secret", REDIRECT.toString());
    static final ProviderSettings DISABLED = new ProviderSettings("", "", "");

    private ProviderTestSupport() {}

    static IdentityProperties properties(ProviderSettings yandex, ProviderSettings vk) {
        return new IdentityProperties(
                List.of(), new Jobs(false), new FakeProviderSettings(false), new Providers(yandex, vk));
    }

    /** Параметры запроса с раскодированными значениями. */
    static Map<String, String> query(URI uri) {
        MultiValueMap<String, String> raw =
                UriComponentsBuilder.fromUri(uri).build().getQueryParams();
        return raw.toSingleValueMap().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> UriUtils.decode(e.getValue(), "UTF-8")));
    }
}
