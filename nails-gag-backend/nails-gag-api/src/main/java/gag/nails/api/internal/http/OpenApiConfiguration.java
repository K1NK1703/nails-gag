package gag.nails.api.internal.http;

import gag.nails.common.actor.Actor;
import gag.nails.identity.model.ClientInfo;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.Locale;
import java.util.Optional;
import org.springdoc.core.customizers.ParameterCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;

/**
 * Схема OpenAPI для локального запуска (спецификация API, §5.5): springdoc подключён в {@code app} только для
 * {@code bootRun}, без него эта настройка не загружается — условие проверяется по имени класса, и других ссылок на
 * этот класс нет.
 *
 * <p>springdoc не знает резолверов слоя и принял бы {@code Actor}, {@code Optional<Actor>} и {@code ClientInfo} за
 * параметры запроса: они скрываются. Провайдер и документ в пути показываются в нижнем регистре, как их принимает
 * адрес ({@link WebConfiguration#LOWER_CASE_PATH_ENUMS}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springdoc.core.utils.SpringDocUtils")
class OpenApiConfiguration {

    OpenApiConfiguration() {
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(Actor.class, ClientInfo.class);
    }

    /**
     * {@code Optional<Actor>} springdoc видит как {@code Optional} и не пропускает по типу: такой параметр убирается
     * здесь. Перечисления в пути получают значения в нижнем регистре.
     */
    @Bean
    ParameterCustomizer apiParameterCustomizer() {
        return (parameter, methodParameter) -> {
            if (optionalActor(methodParameter)) {
                return null;
            }
            if (parameter != null
                    && "path".equals(parameter.getIn())
                    && WebConfiguration.LOWER_CASE_PATH_ENUMS.contains(methodParameter.getParameterType())) {
                lowerCaseEnum(parameter);
            }
            return parameter;
        };
    }

    private static boolean optionalActor(MethodParameter parameter) {
        return parameter.getParameterType() == Optional.class
                && ResolvableType.forMethodParameter(parameter).getGeneric(0).resolve() == Actor.class;
    }

    @SuppressWarnings("unchecked")
    private static void lowerCaseEnum(Parameter parameter) {
        var schema = (Schema<Object>) parameter.getSchema();
        if (schema != null && schema.getEnum() != null) {
            schema.setEnum(schema.getEnum().stream()
                    .map(value -> (Object) value.toString().toLowerCase(Locale.ROOT))
                    .toList());
        }
    }
}
