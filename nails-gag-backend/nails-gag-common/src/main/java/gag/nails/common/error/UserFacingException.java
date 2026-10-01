package gag.nails.common.error;

import java.util.Map;

/**
 * Ошибка, которую показывают пользователю. Несёт постоянный код вида {@code module.reason}
 * и параметры для подстановки в текст; сам текст живёт на фронте. Логируется на уровне WARN.
 */
public abstract class UserFacingException extends RuntimeException {

    private final String code;
    private final Map<String, Object> params;

    protected UserFacingException(String code, Map<String, Object> params) {
        super(code + (params.isEmpty() ? "" : " " + params));
        this.code = code;
        this.params = Map.copyOf(params);
    }

    protected UserFacingException(String code) {
        this(code, Map.of());
    }

    public String code() {
        return code;
    }

    public Map<String, Object> params() {
        return params;
    }
}
