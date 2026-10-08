package gag.nails.identity.testing;

import org.jspecify.annotations.Nullable;

/**
 * Профиль, который подставной провайдер отдаст при обмене кода: как у настоящего провайдера, телефон и
 * почта считаются подтверждёнными.
 *
 * @param subject идентификатор пользователя у провайдера; один и тот же {@code subject} — один человек
 */
public record FakeProfile(
        String subject,
        @Nullable String phone,
        @Nullable String email,
        @Nullable String displayName,
        @Nullable String avatarUrl) {}
