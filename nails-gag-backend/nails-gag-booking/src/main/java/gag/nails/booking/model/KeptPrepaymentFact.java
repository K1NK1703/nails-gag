package gag.nails.booking.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Удержанная предоплата для финансов: исход {@link PrepaymentOutcome#KEPT} с учётом переопределения мастером. Клиента
 * не содержит.
 *
 * @param day день отмены или неявки в поясе салона
 * @param amount сумма полученной предоплаты
 */
public record KeptPrepaymentFact(UUID bookingId, LocalDate day, BigDecimal amount) {}
