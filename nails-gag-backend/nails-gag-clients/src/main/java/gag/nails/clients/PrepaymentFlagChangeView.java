package gag.nails.clients;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Запись журнала признака предоплаты.
 *
 * @param bookingId запись, из-за которой признак изменился; пусто, если причина не связана с записью
 * @param comment пояснение; может отсутствовать
 */
public record PrepaymentFlagChangeView(
        boolean required,
        PrepaymentFlagReason reason,
        ChangedBy setBy,
        @Nullable UUID bookingId,
        @Nullable String comment,
        Instant changedAt) {}
