package gag.nails.common.validation;

import gag.nails.common.error.ValidationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Comparator;
import org.springframework.stereotype.Component;

/**
 * Проверяет команды публичных API по аннотациям Bean Validation и переводит первое нарушение
 * (по алфавиту имён полей, чтобы результат был предсказуем) в {@link ValidationException}.
 * Вызывается явно в начале каждого метода-команды: без AOP и без чужих исключений наружу.
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
                .min(Comparator.comparing(v -> v.getPropertyPath().toString()))
                .ifPresent(CommandValidator::reject);
        return command;
    }

    private static void reject(ConstraintViolation<?> violation) {
        throw new ValidationException(violation.getPropertyPath().toString(), violation.getMessage());
    }
}
