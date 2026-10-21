package gag.nails.portfolio.command;

import gag.nails.portfolio.model.WorkSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Правка работы; файл не меняется. Правила источника, записи и длины полей — как у {@link AddWork}.
 *
 * @param alt описание фото, 1–200 символов
 * @param caption подпись до 200 символов; пустая или из одних пробелов стирает её
 */
public record UpdateWork(
        @NotBlank String alt,
        @Nullable String caption,
        @NotNull WorkSource source,
        @Nullable UUID bookingId) {}
