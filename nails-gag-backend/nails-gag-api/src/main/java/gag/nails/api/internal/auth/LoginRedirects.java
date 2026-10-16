package gag.nails.api.internal.auth;

import gag.nails.api.internal.ApiProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Куда обратный вызов провайдера отправляет браузер (спецификация API, §6.3): {@code 303} на страницу сайта —
 * {@code site-origin} и путь из {@code nails-gag.api.redirects}. Отказ — путь {@code failure} с параметром
 * {@code error}; подробностей в адресе нет, текст фронт выбирает по значению.
 */
@Component
final class LoginRedirects {

    /** Причина отказа во входе — значение параметра {@code error}. */
    enum Failure {
        /** Вход не удался: нет или чужое состояние, пользователь отказал, код не принят. */
        FAILED("failed"),
        /** Карточка клиента заблокирована. */
        BLOCKED("blocked"),
        /** Провайдер недоступен. */
        PROVIDER("provider");

        private final String parameter;

        Failure(String parameter) {
            this.parameter = parameter;
        }
    }

    private final String origin;
    private final ApiProperties.Redirects paths;

    LoginRedirects(ApiProperties properties) {
        this.origin = properties.siteOrigin();
        this.paths = properties.redirects();
    }

    ResponseEntity<Void> signedIn() {
        return to(paths.signedIn());
    }

    ResponseEntity<Void> registration() {
        return to(paths.registration());
    }

    ResponseEntity<Void> linkConfirm() {
        return to(paths.linkConfirm());
    }

    ResponseEntity<Void> linked() {
        return to(paths.linked());
    }

    ResponseEntity<Void> failure(Failure failure) {
        var path = paths.failure();
        return to(path + (path.contains("?") ? "&" : "?") + "error=" + failure.parameter);
    }

    private ResponseEntity<Void> to(String path) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.LOCATION, origin + path)
                .build();
    }
}
