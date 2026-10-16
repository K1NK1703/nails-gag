package gag.nails.api.internal.error;

import gag.nails.booking.error.LimitReachedException;
import gag.nails.common.error.InternalException;
import gag.nails.common.error.ValidationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Тестовые адреса для проверки формата JSON и ошибок слоя; в рабочем приложении их нет. */
@RestController
@RequestMapping("/api/test")
class ProblemTestController {

    static final Instant INSTANT = Instant.parse("2026-10-08T11:30:00Z");
    static final String SECRET = "секрет из исключения";

    @GetMapping("/json")
    JsonSample json() {
        return new JsonSample(
                INSTANT,
                LocalDate.of(2026, 10, 8),
                LocalTime.of(10, 0),
                LocalTime.of(10, 0, 30),
                new BigDecimal("1500.00"),
                new BigDecimal("1E+3"),
                null);
    }

    @PostMapping("/json")
    TimeSample echo(@RequestBody TimeSample body) {
        return body;
    }

    @PostMapping("/validated")
    ValidatedBody validated(@Valid @RequestBody ValidatedBody body) {
        return body;
    }

    /** Бросает исключение по имени: так тест видит, во что его превращает обработчик. */
    @GetMapping("/throw/{kind}")
    void fail(@PathVariable String kind) {
        switch (kind) {
            case "validation" -> throw new ValidationException("phone", "must look like +7XXXXXXXXXX");
            case "limit-reached" -> throw new LimitReachedException(3);
            case "lock" -> throw new PessimisticLockingFailureException("deadlock detected: " + SECRET);
            default -> throw new InternalException(SECRET);
        }
    }

    record JsonSample(
            Instant instant,
            LocalDate date,
            LocalTime time,
            LocalTime timeWithSeconds,
            BigDecimal price,
            BigDecimal amount,
            @Nullable String comment) {}

    record TimeSample(LocalTime time) {}

    record ValidatedBody(
            @NotBlank String name,
            @Pattern(regexp = "^\\+7\\d{10}$") String phone) {}
}
