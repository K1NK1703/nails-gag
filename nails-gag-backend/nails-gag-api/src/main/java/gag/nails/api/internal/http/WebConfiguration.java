package gag.nails.api.internal.http;

import gag.nails.api.internal.security.CurrentActorResolver;
import gag.nails.clients.model.DocumentKind;
import gag.nails.identity.model.Provider;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Аргументы контроллеров слоя: {@code Actor}/{@code Optional<Actor>} вошедшего и {@code ClientInfo} запроса;
 * провайдер входа ({@code /api/auth/yandex/…}) и документ ({@code /api/documents/policy/…}) в пути — по имени в
 * нижнем регистре.
 */
@Configuration(proxyBeanMethods = false)
class WebConfiguration implements WebMvcConfigurer {

    /** Перечисления, которые в пути пишутся по имени в нижнем регистре; так же их показывает схема OpenAPI. */
    static final Set<Class<? extends Enum<?>>> LOWER_CASE_PATH_ENUMS = Set.of(Provider.class, DocumentKind.class);

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentActorResolver());
        resolvers.add(new ClientInfoResolver());
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(new ProviderConverter());
        registry.addConverter(new DocumentKindConverter());
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

    /**
     * {@code policy} → {@link DocumentKind#POLICY}. Как и у провайдера, шаблон адреса пропускает только известные
     * имена.
     */
    private static final class DocumentKindConverter implements Converter<String, DocumentKind> {

        @Override
        public DocumentKind convert(String source) {
            return DocumentKind.valueOf(source.toUpperCase(Locale.ROOT));
        }
    }
}
