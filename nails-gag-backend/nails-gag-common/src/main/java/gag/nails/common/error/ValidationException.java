package gag.nails.common.error;

import java.util.Map;

/** Входные данные не прошли проверку. {@code field} — имя поля или параметра, {@code reason} — почему. */
public final class ValidationException extends UserFacingException {

    private final String field;
    private final String reason;

    public ValidationException(String field, String reason) {
        super("common.validation", Map.of("field", field, "reason", reason));
        this.field = field;
        this.reason = reason;
    }

    public String field() {
        return field;
    }

    public String reason() {
        return reason;
    }
}
