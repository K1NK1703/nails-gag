package gag.nails.identity.internal.provider;

import gag.nails.identity.Provider;
import gag.nails.identity.error.ProviderUnavailableException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Реестр провайдеров по {@link Provider}. Отсутствующий (подставной в бою) или выключенный (пустой
 * {@code client-id}) провайдер для вызывающего неотличимы: {@link ProviderUnavailableException}.
 */
@Component
public class IdentityProviders {

    private final Map<Provider, IdentityProvider> providers = new EnumMap<>(Provider.class);

    IdentityProviders(List<IdentityProvider> registered) {
        registered.forEach(provider -> providers.put(provider.id(), provider));
    }

    /**
     * @throws ProviderUnavailableException провайдер не настроен или его бина нет
     */
    public IdentityProvider get(Provider provider) {
        var found = providers.get(provider);
        if (found == null || !found.enabled()) {
            throw new ProviderUnavailableException();
        }
        return found;
    }
}
