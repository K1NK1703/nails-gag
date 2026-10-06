package gag.nails.identity.internal.provider;

import gag.nails.common.error.InternalException;
import gag.nails.identity.internal.IdentityProperties;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Подставной провайдер в бою — дыра: любой мог бы войти кем угодно. При профиле {@code prod} и
 * {@code nails-gag.identity.fake-provider.enabled=true} приложение не стартует.
 */
@Component
class FakeProviderGuard implements InitializingBean {

    static final String PRODUCTION_PROFILE = "prod";

    private final Environment environment;
    private final IdentityProperties properties;

    FakeProviderGuard(Environment environment, IdentityProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        if (properties.fakeProvider().enabled() && environment.matchesProfiles(PRODUCTION_PROFILE)) {
            throw new InternalException(
                    "nails-gag.identity.fake-provider.enabled=true is forbidden under the prod profile");
        }
    }
}
