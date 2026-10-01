package gag.nails.common.validation;

import gag.nails.common.error.ValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Comparator;
import org.springframework.stereotype.Component;

/**
 * Проверяет команды публичных API по аннотациям Bean Validation и переводит первое нарушение
 * (по алфавиту имён полей, при равенстве — по тексту сообщения, чтобы результат был предсказуем)
 * в {@link ValidationException}. Вызывается явно в начале каждого метода-команды: без AOP и без
 * чужих исключений наружу. Поддерживаются только ограничения на поля (в том числе вложенные через
 * {@code @Valid}); ограничения уровня класса с пустым путём в командах не ожидаются.
 */
@Component
public class CommandValidator {

    private final Validator validator;

    public CommandValidator(Validator validator) {
        this.validator = validator;
    }

    public <T> T requireValid(T command) {
        var violations = validator.validate(command);
        violations.stream()
                .min(Comparator.comparing((ConstraintViolation<?> v) ->
                                v.getPropertyPath().toString())
                        .thenComparing(ConstraintViolation::getMessage))
                .ifPresent(CommandValidator::reject);
        return command;
    }

    private static void reject(ConstraintViolation<?> violation) {
        throw new ValidationException(violation.getPropertyPath().toString(), violation.getMessage());
    }
}
