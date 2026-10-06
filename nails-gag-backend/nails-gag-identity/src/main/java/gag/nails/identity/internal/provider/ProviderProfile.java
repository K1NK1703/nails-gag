package gag.nails.identity.internal.provider;

import gag.nails.common.text.Phones;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Профиль пользователя у провайдера после нормализации.
 *
 * @param subject идентификатор пользователя у провайдера
 * @param phone телефон в E.164, подтверждённый провайдером; {@code null}, если не отдан или нечитаем
 * @param email почта в нижнем регистре, подтверждённая провайдером; {@code null}, если не отдана
 * @param displayName имя для показа
 * @param avatarUrl ссылка на аватар
 */
public record ProviderProfile(
        String subject,
        @Nullable String phone,
        @Nullable String email,
        @Nullable String displayName,
        @Nullable String avatarUrl) {

    /**
     * Собирает профиль из сырых значений провайдера: телефон → E.164 (нечитаемый, например скрытый
     * звёздочками, отбрасывается — лучше без телефона, чем сорвать вход), почта → нижний регистр,
     * пустые строки → {@code null}.
     */
    public static ProviderProfile normalized(
            String subject,
            @Nullable String phone,
            @Nullable String email,
            @Nullable String displayName,
            @Nullable String avatarUrl) {
        return new ProviderProfile(
                subject,
                Phones.parse(phone).orElse(null),
                normalizeEmail(email),
                blankToNull(displayName),
                blankToNull(avatarUrl));
    }

    private static @Nullable String normalizeEmail(@Nullable String email) {
        var trimmed = blankToNull(email);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
