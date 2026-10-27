package gag.nails.api.internal.portfolio;

import gag.nails.portfolio.command.UpdateWork;
import gag.nails.portfolio.model.WorkSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Тело {@code PATCH /api/portfolio/works/{id}}: поля команды {@link UpdateWork}, правила — как у
 * {@link AddPortfolioWork}. Фото, порядок и видимость не меняются.
 *
 * @param alt описание фото, 1–200 символов
 * @param caption подпись до 200 символов; {@code null}, пустая или из пробелов стирает её
 * @param source откуда работа
 * @param bookingId запись клиента — только у {@code CLIENT}, у остальных {@code null}
 */
record UpdatePortfolioWork(
        @NotBlank @Schema(minLength = 1, maxLength = 200) String alt,
        @Schema(maxLength = 200) @Nullable String caption,
        @NotNull WorkSource source,
        @Nullable UUID bookingId) {

    UpdateWork toCommand() {
        return new UpdateWork(alt, caption, source, bookingId);
    }
}
