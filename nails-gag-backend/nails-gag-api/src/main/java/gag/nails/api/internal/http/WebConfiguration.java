package gag.nails.api.internal.http;

import gag.nails.api.internal.security.CurrentActorResolver;
import gag.nails.identity.model.Provider;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Аргументы контроллеров слоя: {@code Actor}/{@code Optional<Actor>} вошедшего и {@code ClientInfo} запроса;
 * провайдер входа в пути ({@code /api/auth/yandex/…}) — по имени в нижнем регистре.
 */
@Configuration(proxyBeanMethods = false)
class WebConfiguration implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentActorResolver());
        resolvers.add(new ClientInfoResolver());
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new ProviderConverter());
    }

    /**
     * {@code yandex} → {@link Provider#YANDEX}. Шаблон адреса пропускает только известные имена, поэтому другое
     * имя до конвертера не доходит, а даёт {@code 404}.
     */
    private static final class ProviderConverter implements Converter<String, Provider> {

        @Override
        public Provider convert(String source) {
            return Provider.valueOf(source.toUpperCase(Locale.ROOT));
        }
    }
}
