package gag.nails.clients;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Изменение признака предоплаты по правилу системы (из модуля записей).
 *
 * @param bookingId запись, из-за которой признак изменился; пусто, если причина не связана с записью
 * @param comment пояснение для журнала; может отсутствовать
 */
public record PrepaymentFlagChange(
        boolean required,
        @NotNull PrepaymentFlagReason reason,
        @Nullable UUID bookingId,
        @Size(max = 500) @Nullable String comment) {}
