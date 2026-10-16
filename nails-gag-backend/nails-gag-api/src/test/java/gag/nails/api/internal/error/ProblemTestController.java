package gag.nails.api.internal.error;

import gag.nails.booking.error.BookingNotFoundException;
import gag.nails.booking.error.LimitReachedException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.InternalException;
import gag.nails.common.error.ValidationException;
import gag.nails.identity.model.ClientInfo;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Тестовые адреса для проверки формата JSON и ошибок слоя; в рабочем приложении их нет. */
@RestController
@RequestMapping("/api/test")
class ProblemTestController {

    static final Instant INSTANT = Instant.parse("2026-10-08T11:30:00Z");
    static final String SECRET = "секрет из исключения";
    static final UUID BOOKING_ID = UUID.fromString("0192a000-0000-7000-8000-000000000001");

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

    /** Обязательный параметр запроса под именем, отличным от имени в Java. */
    @GetMapping("/param")
    int param(@RequestParam("count") int number) {
        return number;
    }

    /** Ограничение на параметре: Spring MVC проверяет его сам и бросает {@code HandlerMethodValidationException}. */
    @GetMapping("/min")
    int min(@RequestParam("size") @Min(1) int pageSize) {
        return pageSize;
    }

    /** Ошибка, выставленная мимо исключений: её отвечает {@code /error}. */
    @GetMapping("/send-error")
    void sendError(HttpServletResponse response) throws IOException {
        response.sendError(HttpStatus.CONTENT_TOO_LARGE.value());
    }

    /** Адрес, которому нужна сессия. */
    @GetMapping("/whoami")
    Caller whoami(Actor actor, ClientInfo client) {
        return new Caller(actor.accountId(), actor.isStaff(), client.ip());
    }

    /** Адрес, работающий и без входа. */
    @GetMapping("/maybe")
    Caller maybe(Optional<Actor> actor, ClientInfo client) {
        return caller(actor, client);
    }

    /** Изменяющий адрес без обязательной сессии: на нём проверяется защита от CSRF. */
    @PostMapping("/echo")
    Caller echo(Optional<Actor> actor, ClientInfo client) {
        return caller(actor, client);
    }

    /** Модуль отказал в доступе: ответ зависит от того, кто спросил. */
    @GetMapping("/denied")
    void denied(Optional<Actor> actor) {
        throw new AccessDeniedException();
    }

    /** Бросает исключение по имени: так тест видит, во что его превращает обработчик. */
    @GetMapping("/throw/{kind}")
    void fail(@PathVariable String kind) {
        switch (kind) {
            case "validation" -> throw new ValidationException("phone", "must look like +7XXXXXXXXXX");
            case "limit-reached" -> throw new LimitReachedException(3);
            case "booking-not-found" -> throw new BookingNotFoundException(BOOKING_ID);
            case "access-denied" -> throw new AccessDeniedException();
            case "unavailable" -> throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, SECRET);
            case "lock" -> throw new PessimisticLockingFailureException("deadlock detected: " + SECRET);
            default -> throw new InternalException(SECRET);
        }
    }

    private static Caller caller(Optional<Actor> actor, ClientInfo client) {
        return new Caller(
                actor.map(Actor::accountId).orElse(null),
                actor.map(Actor::isStaff).orElse(false),
                client.ip());
    }

    record Caller(
            @Nullable UUID accountId,
            boolean staff,
            @Nullable String ip) {}

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
