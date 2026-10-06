package gag.nails.identity.internal.provider;

import static gag.nails.identity.internal.provider.ProviderTestSupport.CONFIGURED;
import static gag.nails.identity.internal.provider.ProviderTestSupport.DISABLED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.identity.Provider;
import gag.nails.identity.error.ProviderUnavailableException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class IdentityProvidersTest {

    @Test
    void returnsEnabledProviderAndRejectsDisabledOrMissing() {
        var http = RestClient.create();
        var properties = ProviderTestSupport.properties(CONFIGURED, DISABLED);
        var yandex = new YandexIdentityProvider(properties, http);
        var providers = new IdentityProviders(List.of(yandex, new VkIdentityProvider(properties, http)));

        assertThat(providers.get(Provider.YANDEX)).isSameAs(yandex);
        assertThatThrownBy(() -> providers.get(Provider.VK)).isInstanceOf(ProviderUnavailableException.class);
        assertThatThrownBy(() -> providers.get(Provider.FAKE)).isInstanceOf(ProviderUnavailableException.class);
    }
}
