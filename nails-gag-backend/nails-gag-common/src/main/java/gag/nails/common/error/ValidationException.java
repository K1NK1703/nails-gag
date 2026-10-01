package gag.nails.common.error;

import java.util.Map;

/** Входные данные не прошли проверку. {@code field} — имя поля или параметра, {@code reason} — почему. */
public final class ValidationException extends UserFacingException {

    public ValidationException(String field, String reason) {
        super("common.validation", Map.of("field", field, "reason", reason));
    }
}
