package gag.nails.catalog.command;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/** Правка услуги. Вид услуги не меняется: дополнение не может стать основной услугой. */
public record UpdateService(
        @NotBlank @Size(max = 200) String title,
        @NotNull @Size(max = 2000) String description,

        @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2)
        BigDecimal price,

        @Positive @Nullable Integer durationMinutes,
        @NotBlank @Size(max = 100) String category,
        boolean showOnLanding,
        boolean highlightOnLanding) {}
