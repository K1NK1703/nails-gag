package gag.nails.identity.internal.provider;

import java.time.Duration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * HTTP-клиент для Яндекс ID и VK ID: таймауты подключения и чтения по 5 секунд — вход не должен
 * висеть, если провайдер молчит. Строится из автоконфигурированного {@link RestClient.Builder}, чтобы
 * сохранить наблюдаемость Spring Boot; фабрика запросов своя, с нашими таймаутами.
 */
@Configuration(proxyBeanMethods = false)
class ProviderConfiguration {

    static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Bean
    RestClient identityProviderRestClient(RestClient.Builder builder) {
        var settings = HttpClientSettings.defaults().withConnectTimeout(TIMEOUT).withReadTimeout(TIMEOUT);
        return builder.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }
}
