package gag.nails.api.internal;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.common.error.InternalException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

class ApiPropertiesGuardTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(GuardedConfiguration.class);

    @Test
    void rejectsRelativeOrPathfulSiteOrigin() {
        List.of(
                        "/",
                        "nails.gag",
                        "https://nails.gag/",
                        "https://nails.gag/app",
                        "https://nails.gag?x=1",
                        "https://user@nails.gag",
                        "ht tp://nails.gag",
                        "HTTPS://nails.gag",
                        "https://Nails.gag",
                        "https://nails.gag:443",
                        "http://localhost:80")
                .forEach(siteOrigin -> assertStopsTheApplication("nails-gag.api.site-origin=" + siteOrigin));
    }

    @Test
    void rejectsPlainHttpOutsideLocalhost() {
        List.of("http://nails.gag", "http://127.0.0.1:8080", "ftp://nails.gag")
                .forEach(siteOrigin -> assertStopsTheApplication("nails-gag.api.site-origin=" + siteOrigin));
    }

    @Test
    void rejectsKeyOfWrongLength() {
        List.of(
                        Base64.getEncoder().encodeToString(new byte[16]),
                        Base64.getEncoder().encodeToString(new byte[33]),
                        "not base64!")
                .forEach(key -> assertStopsTheApplication("nails-gag.api.login-state-key=" + key));
    }

    @Test
    void prodRequiresKeyAndSecureCookies() {
        assertStopsTheApplication("spring.profiles.active=prod");
        assertStopsTheApplication(
                "spring.profiles.active=prod",
                "nails-gag.api.login-state-key=" + KEY,
                "nails-gag.api.cookies.secure=false");
        runner.withPropertyValues("spring.profiles.active=prod", "nails-gag.api.login-state-key=" + KEY)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void acceptsLocalDefaults() {
        runner.withPropertyValues("nails-gag.api.site-origin=https://nails.gag:8443")
                .run(context -> assertThat(context).hasNotFailed());
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ApiPropertiesGuard.class);
            var properties = context.getBean(ApiProperties.class);
            assertThat(properties.siteOrigin()).isEqualTo("https://nails.gag");
            assertThat(properties.cookies().secure()).isTrue();
            assertThat(properties.loginStateKey()).isEmpty();
            assertThat(properties.rateLimits()).isEqualTo(new ApiProperties.RateLimits(10, 60, 600));
            assertThat(properties.redirects())
                    .isEqualTo(new ApiProperties.Redirects(
                            "/cabinet",
                            "/register",
                            "/cabinet/account?link=confirm",
                            "/cabinet/account?link=done",
                            "/login"));
        });
        runner.withPropertyValues(
                        "spring.profiles.active=local",
                        "nails-gag.api.site-origin=http://localhost:5173",
                        "nails-gag.api.cookies.secure=false",
                        "nails-gag.api.login-state-key=" + KEY)
                .run(context -> assertThat(context).hasNotFailed());
    }

    private void assertStopsTheApplication(String... properties) {
        runner.withPropertyValues(properties).run(context -> {
            assertThat(context).as(String.join(", ", properties)).hasFailed();
            assertThat(causeChain(context.getStartupFailure())).anyMatch(InternalException.class::isInstance);
        });
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
    @EnableConfigurationProperties(ApiProperties.class)
    static class GuardedConfiguration {

        @Bean
        ApiPropertiesGuard apiPropertiesGuard(Environment environment, ApiProperties properties) {
            return new ApiPropertiesGuard(environment, properties);
        }
    }
}
