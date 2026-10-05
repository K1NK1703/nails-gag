package gag.nails.booking;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Новая запись: клиент, слот и состав — одна основная услуга и любое число дополнений без повторов.
 *
 * @param comment пожелания клиента; может отсутствовать
 */
public record CreateBooking(
        @NotNull UUID clientId,
        @NotNull UUID slotId,
        @NotNull UUID mainServiceId,
        @NotNull @Size(max = 20) List<@NotNull UUID> addonServiceIds,
        @Size(max = 500) @Nullable String comment) {}
