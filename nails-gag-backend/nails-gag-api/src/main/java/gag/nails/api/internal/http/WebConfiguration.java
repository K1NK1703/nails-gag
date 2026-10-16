package gag.nails.api.internal.http;

import gag.nails.api.internal.security.CurrentActorResolver;
import gag.nails.clients.model.DocumentKind;
import gag.nails.identity.model.Provider;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Configuration;
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

    /** Перечисления, которые в пути пишутся по имени в нижнем регистре: провайдер входа и документ. */
    private static final List<LowerCaseEnum<?>> PATH_ENUMS =
            List.of(new LowerCaseEnum<>(Provider.class), new LowerCaseEnum<>(DocumentKind.class));

    /** Типы из {@link #PATH_ENUMS}; по ним же схема OpenAPI показывает значения в нижнем регистре. */
    static final Set<Class<?>> LOWER_CASE_PATH_ENUMS =
            PATH_ENUMS.stream().map(LowerCaseEnum::type).collect(Collectors.toUnmodifiableSet());

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentActorResolver());
        resolvers.add(new ClientInfoResolver());
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        PATH_ENUMS.forEach(pathEnum -> pathEnum.register(registry));
    }

    /**
     * {@code yandex} → {@code Provider.YANDEX}, {@code policy} → {@code DocumentKind.POLICY}. Шаблон адреса пропускает
     * только известные имена, поэтому другое имя до конвертера не доходит, а даёт {@code 404}.
     */
    private record LowerCaseEnum<E extends Enum<E>>(Class<E> type) {

        void register(FormatterRegistry registry) {
            registry.addConverter(String.class, type, source -> Enum.valueOf(type, source.toUpperCase(Locale.ROOT)));
        }
    }
}
