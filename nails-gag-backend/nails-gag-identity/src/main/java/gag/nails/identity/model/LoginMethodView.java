package gag.nails.identity.model;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Способ входа аккаунта; {@code subject} провайдера наружу не отдаётся. */
public record LoginMethodView(
        Provider provider,
        @Nullable String displayName,
        @Nullable String avatarUrl,
        Instant linkedAt,
        Instant lastUsedAt) {}
