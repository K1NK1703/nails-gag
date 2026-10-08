package gag.nails.catalog.command;

import gag.nails.catalog.model.ServiceKind;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * Новая услуга. {@code durationMinutes} обязательна для {@link ServiceKind#MAIN} и запрещена для
 * {@link ServiceKind#ADDON} — это проверяет сервис, а не аннотации.
 */
public record CreateService(
        @NotNull ServiceKind kind,
        @NotBlank @Size(max = 200) String title,
        @NotNull @Size(max = 2000) String description,

        @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2)
        BigDecimal price,

        @Positive @Nullable Integer durationMinutes,
        @NotBlank @Size(max = 100) String category,
        boolean showOnLanding,
        boolean highlightOnLanding) {}
