package gag.nails.api.internal.error;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Тестовые адреса для проверки формата JSON и ошибок слоя; в рабочем приложении их нет. */
@RestController
@RequestMapping("/api/test")
class ProblemTestController {

    static final Instant INSTANT = Instant.parse("2026-10-08T11:30:00Z");

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

    record JsonSample(
            Instant instant,
            LocalDate date,
            LocalTime time,
            LocalTime timeWithSeconds,
            BigDecimal price,
            BigDecimal amount,
            @Nullable String comment) {}

    record TimeSample(LocalTime time) {}
}
