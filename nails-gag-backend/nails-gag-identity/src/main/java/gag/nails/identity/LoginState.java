package gag.nails.identity;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Состояние начатого входа. HTTP-слой хранит его в короткоживущей cookie (HttpOnly) и возвращает в
 * {@link LoginFlow#complete}. Секретно: содержит {@code codeVerifier} PKCE.
 *
 * @param accountId аккаунт вошедшего при {@link LoginPurpose#LINK}; у {@link LoginPurpose#LOGIN} пусто
 * @param issuedAt когда начат вход; состояние действует 10 минут
 */
public record LoginState(
        String state,
        String codeVerifier,
        LoginPurpose purpose,
        @Nullable UUID accountId,
        Instant issuedAt) {

    /** Без {@code state} и {@code codeVerifier}: их место — cookie, а не логи. */
    @Override
    public String toString() {
        return "LoginState[purpose=" + purpose + ", accountId=" + accountId + ", issuedAt=" + issuedAt + "]";
    }
}
