package gag.nails.api.internal.http;

import gag.nails.api.internal.security.CurrentActorResolver;
import gag.nails.clients.model.DocumentKind;
import gag.nails.common.actor.Section;
import gag.nails.common.error.ValidationException;
import gag.nails.identity.model.Provider;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Аргументы контроллеров слоя: {@code Actor}/{@code Optional<Actor>} вошедшего и {@code ClientInfo} запроса;
 * перечисления в пути и параметрах запроса — провайдер входа ({@code /api/auth/yandex/…}), документ
 * ({@code /api/documents/policy/…}), раздел админки ({@code …/overrides/booking-settings}), записи клиента
 * ({@code ?scope=history}) — по имени в нижнем регистре через дефис.
 */
@Configuration(proxyBeanMethods = false)
class WebConfiguration implements WebMvcConfigurer {

    /** Перечисления, которые в адресе пишутся по имени в нижнем регистре через дефис. */
    private static final List<KebabCaseEnum<?>> KEBAB_CASE_ENUMS = List.of(
            KebabCaseEnum.of(Provider.class),
            KebabCaseEnum.of(DocumentKind.class),
            KebabCaseEnum.of(Section.class),
            KebabCaseEnum.of(BookingScope.class));

    /** Типы из {@link #KEBAB_CASE_ENUMS}; по ним же схема OpenAPI показывает значения через дефис. */
    static final Set<Class<?>> KEBAB_CASE_TYPES =
            KEBAB_CASE_ENUMS.stream().map(KebabCaseEnum::type).collect(Collectors.toUnmodifiableSet());

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentActorResolver());
        resolvers.add(new ClientInfoResolver());
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        KEBAB_CASE_ENUMS.forEach(kebabCaseEnum -> kebabCaseEnum.register(registry));
    }

    /** Имя константы в адресе: {@code BOOKING_SETTINGS} → {@code booking-settings}. */
    static String kebabCase(String constantName) {
        return constantName.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * {@code yandex} → {@code Provider.YANDEX}, {@code booking-settings} → {@code Section.BOOKING_SETTINGS}.
     * Принимается только такое написание: имя константы Java ({@code BOOKING_SETTINGS}) и прочие строки — неверное
     * значение параметра, {@code 400 common.validation} с его именем. У провайдера и документа шаблон адреса пропускает
     * лишь известные имена, поэтому другое имя до конвертера не доходит, а даёт {@code 404}.
     */
    private record KebabCaseEnum<E extends Enum<E>>(Class<E> type, Map<String, E> constants) {

        static <E extends Enum<E>> KebabCaseEnum<E> of(Class<E> type) {
            var constants = Arrays.stream(type.getEnumConstants())
                    .collect(Collectors.toUnmodifiableMap(constant -> kebabCase(constant.name()), Function.identity()));
            return new KebabCaseEnum<>(type, constants);
        }

        void register(FormatterRegistry registry) {
            registry.addConverter(String.class, type, this::parse);
        }

        private E parse(String source) {
            var constant = constants.get(source);
            if (constant == null) {
                // Аргументы исключения в ответ не попадают: Spring оборачивает отказ конвертера в
                // MethodArgumentTypeMismatchException, и ProblemHandler берёт поле из имени параметра.
                throw new ValidationException(type.getSimpleName(), "unknown value");
            }
            return constant;
        }
    }
}
