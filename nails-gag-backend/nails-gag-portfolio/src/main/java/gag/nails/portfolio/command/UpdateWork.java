package gag.nails.portfolio.command;

import gag.nails.portfolio.model.WorkSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Правка работы; файл не меняется. Правила источника и записи — как у {@link AddWork}.
 *
 * @param caption подпись; пустая или из одних пробелов стирает её
 */
public record UpdateWork(
        @NotBlank @Size(max = 200) String alt,
        @Size(max = 200) @Nullable String caption,
        @NotNull WorkSource source,
        @Nullable UUID bookingId) {}
