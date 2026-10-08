package gag.nails.identity.internal.provider;

import gag.nails.identity.error.LoginFailedException;
import gag.nails.identity.error.ProviderUnavailableException;
import java.net.URI;
import java.util.SequencedMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.springframework.web.client.RestClient.ResponseSpec.ErrorHandler;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Общее для Яндекс ID и VK ID: строгое кодирование параметров адреса авторизации и перевод ответов
 * HTTP в исключения модуля. В лог попадает только шаг и статус ответа — ни тело, ни токены.
 */
final class ProviderHttp {

    private ProviderHttp() {}

    /** Адрес с параметрами; значения кодируются целиком ({@code :} и {@code /} внутри — тоже). */
    static URI authorizationUri(String endpoint, SequencedMap<String, String> params) {
        var builder = UriComponentsBuilder.fromUriString(endpoint);
        params.keySet().forEach(name -> builder.queryParam(name, "{" + name + "}"));
        return builder.encode().buildAndExpand(params).toUri();
    }

    /** Обмен кода: {@code 4xx} — код отвергнут, иное — провайдер недоступен. */
    static ErrorHandler codeRejectedOn4xx(Logger log, String step) {
        return (request, response) -> {
            var status = response.getStatusCode();
            log.warn("{}: provider answered {}", step, status.value());
            if (status.is4xxClientError()) {
                throw new LoginFailedException();
            }
            throw new ProviderUnavailableException();
        };
    }

    /** Запрос профиля по свежему токену: любая ошибка — провайдер недоступен, пользователь не виноват. */
    static ErrorHandler unavailableOnError(Logger log, String step) {
        return (request, response) -> {
            log.warn("{}: provider answered {}", step, response.getStatusCode().value());
            throw new ProviderUnavailableException();
        };
    }

    /**
     * Выполняет запрос: сеть, таймаут и нечитаемый ответ → {@link ProviderUnavailableException} с
     * причиной; пустое тело при успешном статусе — тоже недоступность. Исключения модуля из
     * обработчиков статусов проходят как есть.
     */
    static <T> T call(Logger log, String step, Supplier<@Nullable T> request) {
        T body;
        try {
            body = request.get();
        } catch (RestClientException e) {
            log.warn("{}: request failed with {}", step, e.getClass().getSimpleName());
            throw new ProviderUnavailableException(e);
        }
        if (body == null) {
            log.warn("{}: provider answered without a body", step);
            throw new ProviderUnavailableException();
        }
        return body;
    }
}
