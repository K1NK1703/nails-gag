package gag.nails.identity.model;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Строка журнала входов.
 *
 * @param accountId пусто у неудачного входа без аккаунта и у удалённых аккаунтов
 */
public record LoginEventView(
        UUID id,
        @Nullable UUID accountId,
        Provider provider,
        LoginOutcome outcome,
        @Nullable String ip,
        @Nullable String userAgent,
        Instant occurredAt) {}
