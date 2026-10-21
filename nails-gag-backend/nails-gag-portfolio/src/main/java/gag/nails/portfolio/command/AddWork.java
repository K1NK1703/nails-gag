package gag.nails.portfolio.command;

import gag.nails.portfolio.model.WorkSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Новая работа. {@code bookingId} обязателен для {@link WorkSource#CLIENT} и запрещён для остальных источников —
 * это проверяет сервис, а не аннотации.
 *
 * @param fileId загруженный тем же аккаунтом и ещё не привязанный файл назначения {@code PORTFOLIO}
 * @param caption подпись; пустая или из одних пробелов не сохраняется
 */
public record AddWork(
        @NotNull UUID fileId,
        @NotBlank @Size(max = 200) String alt,
        @Size(max = 200) @Nullable String caption,
        @NotNull WorkSource source,
        @Nullable UUID bookingId) {}
