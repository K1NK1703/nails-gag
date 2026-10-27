package gag.nails.api.internal.portfolio;

import gag.nails.portfolio.command.AddWork;
import gag.nails.portfolio.model.WorkSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Тело {@code POST /api/portfolio/works}: поля команды {@link AddWork}. Обязательность — та же, что у команды; длину
 * текстов проверяет модуль в символах (эмодзи — один), а не аннотацией в единицах UTF-16 — здесь она только в схеме
 * для фронта.
 *
 * @param fileId фото, загруженное этим же аккаунтом с назначением {@code portfolio} и ещё не занятое
 * @param alt описание фото, 1–200 символов
 * @param caption подпись до 200 символов; {@code null}, пустая или из пробелов — без подписи
 * @param source откуда работа; от него зависит, нужно ли согласие клиента
 * @param bookingId запись клиента — только у {@code CLIENT}, у остальных {@code null}
 */
record AddPortfolioWork(
        @NotNull UUID fileId,
        @NotBlank @Schema(minLength = 1, maxLength = 200) String alt,
        @Schema(maxLength = 200) @Nullable String caption,
        @NotNull WorkSource source,
        @Nullable UUID bookingId) {

    AddWork toCommand() {
        return new AddWork(fileId, alt, caption, source, bookingId);
    }
}
