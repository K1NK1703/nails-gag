package gag.nails.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.common.error.InternalException;
import gag.nails.identity.internal.IdentityProperties.ProviderSettings;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class IdentityPropertiesGuardTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(GuardedConfiguration.class);

    @Test
    void defaultsStart() {
        runner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(IdentityPropertiesGuard.class));
    }

    @Test
    void httpsRedirectAndLocalhostHttpAreAccepted() {
        runner.withPropertyValues(
                        "nails-gag.identity.providers.yandex.client-id=y",
                        "nails-gag.identity.providers.yandex.client-secret=s",
                        "nails-gag.identity.providers.yandex.redirect-uri=https://nails.gag/auth/yandex",
                        "nails-gag.identity.providers.vk.client-id=v",
                        "nails-gag.identity.providers.vk.redirect-uri=http://localhost:8080/auth/vk",
                        "nails-gag.identity.bootstrap-developers=YANDEX:1234567,VK:7654321")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"http://nails.gag/auth/yandex", "/auth/yandex", "nails.gag/auth", "https://", "ht tp://nails.gag"
            })
    void badRedirectUriStopsTheApplication(String redirectUri) {
        runner.withPropertyValues(
                        "nails-gag.identity.providers.yandex.client-id=y",
                        "nails-gag.identity.providers.yandex.redirect-uri=" + redirectUri)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(causeChain(context.getStartupFailure())).anyMatch(InternalException.class::isInstance);
                });
    }

    @Test
    void configuredProviderWithoutRedirectUriStopsTheApplication() {
        runner.withPropertyValues("nails-gag.identity.providers.vk.client-id=v").run(context -> {
            assertThat(context).hasFailed();
            assertThat(causeChain(context.getStartupFailure())).anyMatch(InternalException.class::isInstance);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234567", "GOOGLE:1", ":1", "YANDEX:", "yandex:1"})
    void badBootstrapEntryStopsTheApplication(String entry) {
        runner.withPropertyValues("nails-gag.identity.bootstrap-developers=" + entry)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(causeChain(context.getStartupFailure())).anyMatch(InternalException.class::isInstance);
                });
    }

    @Test
    void providerSettingsHideTheSecretAndRequireIdWithRedirect() {
        var settings = new ProviderSettings("app", "top-secret", "https://nails.gag/cb");

        assertThat(settings.toString()).contains("app", "https://nails.gag/cb").doesNotContain("top-secret");
        assertThat(settings.enabled()).isTrue();
        assertThat(new ProviderSettings("app", "", "https://nails.gag/cb").enabled())
                .isTrue();
        assertThat(new ProviderSettings("", "s", "https://nails.gag/cb").enabled())
                .isFalse();
        assertThat(new ProviderSettings("app", "s", "").enabled()).isFalse();
    }

    /** Исключение стража — не всегда корневое: при кривом адресе причиной остаётся {@code URISyntaxException}. */
    private static List<Throwable> causeChain(Throwable failure) {
        var chain = new ArrayList<Throwable>();
        for (var t = failure; t != null && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        return chain;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IdentityProperties.class)
    static class GuardedConfiguration {

        @Bean
        IdentityPropertiesGuard identityPropertiesGuard(IdentityProperties properties) {
            return new IdentityPropertiesGuard(properties);
        }
    }
}
