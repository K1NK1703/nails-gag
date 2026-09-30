package gag.nails.common.error;

/**
 * Ошибка, которую видит только разработчик в логах (уровень ERROR, со стеком и trace id).
 * Пользователь получает общий ответ. Модули наследуют её в своих пакетах {@code internal.error}
 * или бросают напрямую.
 */
public class InternalException extends RuntimeException {

    public InternalException(String message, Throwable cause) {
        super(message, cause);
    }

    public InternalException(String message) {
        super(message);
    }
}
