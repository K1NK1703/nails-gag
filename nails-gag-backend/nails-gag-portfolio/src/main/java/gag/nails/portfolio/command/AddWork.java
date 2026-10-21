package gag.nails.portfolio.command;

import gag.nails.portfolio.model.WorkSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Новая работа. {@code bookingId} обязателен для {@link WorkSource#CLIENT} и запрещён для остальных источников, а
 * длину {@code alt} и {@code caption} (до 200 символов — кодовых точек, как {@code char_length} в базе) — это
 * проверяет сервис, а не аннотации: {@code @Size} считает единицы UTF-16, эмодзи для него — два символа.
 *
 * @param fileId загруженный тем же аккаунтом и ещё не привязанный файл назначения {@code PORTFOLIO}
 * @param alt описание фото, 1–200 символов
 * @param caption подпись до 200 символов; пустая или из одних пробелов не сохраняется
 */
public record AddWork(
        @NotNull UUID fileId,
        @NotBlank String alt,
        @Nullable String caption,
        @NotNull WorkSource source,
        @Nullable UUID bookingId) {}
