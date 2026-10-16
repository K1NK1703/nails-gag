package gag.nails.api.internal.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalTime;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * JSON слоя {@code /api}. Меняет только JSON-конвертер Spring MVC — копию общего {@link JsonMapper} приложения;
 * сам общий {@code JsonMapper} не трогается: им пользуется {@code RestClient} провайдеров входа, которому
 * неизвестные поля в ответах Яндекса и VK должны быть безразличны.
 *
 * <p>Отличия от общего: неизвестное поле в теле запроса — ошибка (опечатка не пропадает молча); {@code null}
 * пишется явно (типы фронта — {@code string | null}); {@code BigDecimal} — без экспоненты; {@code LocalTime} —
 * {@code "10:00"}, если секунд и долей нет, иначе ISO ({@code "10:00:30"}). Читается {@code LocalTime} и в виде
 * {@code "HH:mm"}, и в виде {@code "HH:mm:ss"}. Моменты, даты, дни недели и UUID — как пишет Jackson по
 * умолчанию: ISO, {@code Instant} в UTC с {@code Z}.
 *
 * <p>Тот же {@link #mapper()} пишет тела ошибок из фильтров, чтобы формат не зависел от того, кто ответил.
 */
@Configuration(proxyBeanMethods = false)
@Order(Ordered.LOWEST_PRECEDENCE)
public class JsonConfiguration implements WebMvcConfigurer {

    private final JsonMapper mapper;

    JsonConfiguration(JsonMapper applicationMapper) {
        this.mapper = applicationMapper
                .rebuild()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.ALWAYS))
                // LocalTime.toString() — ISO-8601 без нулевых секунд: "10:00", "10:00:30", "10:00:30.5".
                .addModule(
                        new SimpleModule("nails-gag-api").addSerializer(LocalTime.class, ToStringSerializer.instance))
                .build();
    }

    /** Mapper слоя {@code /api}; общий {@code JsonMapper} приложения остаётся как был. */
    public JsonMapper mapper() {
        return mapper;
    }

    /**
     * Вызывается после настройки Spring Boot (у той порядок 0) и заменяет его JSON-конвертер своим: последний
     * {@code withJsonConverter} побеждает.
     */
    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        builder.withJsonConverter(new JacksonJsonHttpMessageConverter(mapper));
    }
}
