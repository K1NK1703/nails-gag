package gag.nails.api.internal.http;

import gag.nails.api.internal.security.CurrentActorResolver;
import gag.nails.clients.model.DocumentKind;
import gag.nails.common.actor.Section;
import gag.nails.common.error.ValidationException;
import gag.nails.identity.model.Provider;
import java.beans.PropertyEditorSupport;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.bind.WebDataBinder;
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

    /**
     * Те же правила в службе преобразований — для её прямых вызовов; аргументы контроллеров разбирают редакторы
     * ({@link #registerKebabCaseEditors}).
     */
    @Override
    public void addFormatters(FormatterRegistry registry) {
        KEBAB_CASE_ENUMS.forEach(kebabCaseEnum -> kebabCaseEnum.register(registry));
    }

    /**
     * Перечисления из {@link #KEBAB_CASE_ENUMS} для аргументов контроллеров ({@code @PathVariable},
     * {@code @RequestParam}): редактор на каждый тип, неверное значение — {@link ValidationException} с именем
     * параметра. Конвертеров из {@link #addFormatters} для этого мало: их отказ Spring проглатывает и пробует
     * {@code Enum.valueOf}, так что имя константы Java ({@code BOOKING_SETTINGS}) прошло бы. С редактором службу
     * преобразований Spring не вызывает и обходного пути нет.
     *
     * @param binder привязка одного аргумента; её {@code objectName} — имя параметра
     */
    static void registerKebabCaseEditors(WebDataBinder binder) {
        var parameter = binder.getObjectName();
        KEBAB_CASE_ENUMS.forEach(kebabCaseEnum -> kebabCaseEnum.registerEditor(binder, parameter));
    }

    /** Имя константы в адресе: {@code BOOKING_SETTINGS} → {@code booking-settings}. */
    static String kebabCase(String constantName) {
        return constantName.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * {@code yandex} → {@code Provider.YANDEX}, {@code booking-settings} → {@code Section.BOOKING_SETTINGS}.
     * Принимается только такое написание: имя константы Java ({@code BOOKING_SETTINGS}), другой регистр, пробелы и
     * прочие строки — неверное значение параметра, {@code 400 common.validation} с его именем. У провайдера и документа
     * шаблон адреса пропускает лишь известные имена, поэтому другое имя до разбора не доходит, а даёт {@code 404}.
     */
    private record KebabCaseEnum<E extends Enum<E>>(Class<E> type, Map<String, E> constants) {

        static <E extends Enum<E>> KebabCaseEnum<E> of(Class<E> type) {
            var constants = Arrays.stream(type.getEnumConstants())
                    .collect(Collectors.toUnmodifiableMap(constant -> kebabCase(constant.name()), Function.identity()));
            return new KebabCaseEnum<>(type, constants);
        }

        void register(FormatterRegistry registry) {
            registry.addConverter(String.class, type, source -> parse(source, type.getSimpleName()));
        }

        /**
         * Исключение редактора Spring не оборачивает: {@link ValidationException} доходит до {@code ProblemHandler} как
         * есть, поэтому поле в нём — имя параметра.
         */
        void registerEditor(WebDataBinder binder, String parameter) {
            binder.registerCustomEditor(type, new PropertyEditorSupport() {
                @Override
                public void setAsText(String text) {
                    setValue(parse(text, parameter));
                }
            });
        }

        private E parse(String source, String field) {
            var constant = constants.get(source);
            if (constant == null) {
                throw new ValidationException(field, "unknown value");
            }
            return constant;
        }
    }
}
