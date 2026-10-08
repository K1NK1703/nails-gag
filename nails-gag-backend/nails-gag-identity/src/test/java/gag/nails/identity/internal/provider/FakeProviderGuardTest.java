package gag.nails.identity.internal.provider;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.common.error.InternalException;
import gag.nails.identity.internal.IdentityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

class FakeProviderGuardTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(GuardedConfiguration.class);

    @Test
    void fakeProviderUnderProdProfileStopsTheApplication() {
        runner.withPropertyValues("spring.profiles.active=prod", "nails-gag.identity.fake-provider.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(InternalException.class);
                });
    }

    @Test
    void fakeProviderUnderLocalProfileStarts() {
        runner.withPropertyValues("spring.profiles.active=local", "nails-gag.identity.fake-provider.enabled=true")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(FakeProviderGuard.class));
    }

    @Test
    void prodWithoutFakeProviderStarts() {
        runner.withPropertyValues("spring.profiles.active=prod", "nails-gag.identity.fake-provider.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IdentityProperties.class)
    static class GuardedConfiguration {

        @Bean
        FakeProviderGuard fakeProviderGuard(Environment environment, IdentityProperties properties) {
            return new FakeProviderGuard(environment, properties);
        }
    }
}
