package gag.nails.api.internal;

import java.util.Locale;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApiProperties.class)
class ApiConfiguration {

    /**
     * Язык обработки запросов — всегда английский, независимо от {@code Accept-Language} и языка сервера. Тексты для
     * человека живут на фронте; причины в {@code errors} (сообщения Bean Validation) — технические и должны быть
     * одинаковыми у всех. Spring Boot свой {@code localeResolver} при этом не создаёт.
     */
    @Bean(DispatcherServlet.LOCALE_RESOLVER_BEAN_NAME)
    LocaleResolver localeResolver() {
        return new FixedLocaleResolver(Locale.ENGLISH);
    }
}
