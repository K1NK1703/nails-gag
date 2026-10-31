package gag.nails.booking.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Денежный факт выполненного визита для финансов. Клиента не содержит.
 *
 * @param day день визита в поясе салона: по согласованному более раннему времени, если оно есть, иначе по началу
 *     слота
 * @param services фактический состав: основная услуга первой, затем дополнения
 * @param priceSum прайс фактического состава — сумма цен {@code services}
 * @param finalAmount итоговая сумма визита — сколько получено
 * @param amountNote причина итоговой суммы; может отсутствовать
 */
public record VisitFact(
        UUID bookingId,
        LocalDate day,
        List<VisitServiceFact> services,
        BigDecimal priceSum,
        BigDecimal finalAmount,
        @Nullable String amountNote) {}
