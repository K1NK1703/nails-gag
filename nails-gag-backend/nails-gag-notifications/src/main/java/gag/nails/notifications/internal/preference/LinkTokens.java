package gag.nails.notifications.internal.preference;

import gag.nails.common.error.InternalException;
import gag.nails.common.id.UuidV7;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Одноразовые токены ссылки привязки бота (спека §3.6): 32 случайных байта в base64url без {@code =} — 43 символа из
 * {@code [A-Za-z0-9_-]}, как Telegram принимает в {@code ?start=}. В базе — только SHA-256 токена: утечка таблицы не
 * даёт привязать чужой аккаунт. Токен живёт {@link #LIFETIME}; у аккаунта один токен — новый заменяет прежний.
 *
 * <p>Гашение — первый блокирующий оператор привязки: удаление строки токена ({@code DELETE … RETURNING}). Так привязка
 * встаёт в порядок блокировок модуля «токен → чат → настройки каналов» ({@link AccountChannels}) и встречается с
 * удалением аккаунта на строке токена.
 */
@Component
public class LinkTokens {

    /** Сколько живёт ссылка привязки. */
    public static final Duration LIFETIME = Duration.ofMinutes(10);

    private static final int TOKEN_BYTES = 32;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final Clock clock;

    LinkTokens(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Новый токен аккаунта вместо прежнего; вызывается в транзакции. */
    Issued issue(UUID accountId) {
        var bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var expiresAt = clock.instant().plus(LIFETIME);
        jdbc.sql("""
                        insert into notifications.link_token (id, account_id, token_hash, expires_at)
                        values (:id, :accountId, :hash, :expiresAt)
                        on conflict (account_id) do update
                        set token_hash = excluded.token_hash, expires_at = excluded.expires_at
                        """)
                .param("id", UuidV7.next())
                .param("accountId", accountId)
                .param("hash", hash(token))
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
        return new Issued(token, expiresAt);
    }

    /**
     * Гасит токен: удаляет его строку, даже истёкшую, и называет аккаунт, если токен ещё жив. Вызывается в транзакции
     * привязки, раньше любых изменений чата: строка токена остаётся заблокированной до её конца.
     *
     * @return аккаунт ссылки; пусто — токен не такой формы, неизвестен (погашен, заменён) или истёк
     */
    public Optional<UUID> consume(String token) {
        if (!TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        var now = clock.instant();
        return jdbc.sql("""
                        delete from notifications.link_token where token_hash = :hash
                        returning account_id, expires_at
                        """)
                .param("hash", hash(token))
                .query((row, number) -> new Consumed(
                        row.getObject("account_id", UUID.class),
                        row.getTimestamp("expires_at").toInstant()))
                .optional()
                .filter(consumed -> consumed.expiresAt().isAfter(now))
                .map(Consumed::accountId);
    }

    private static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new InternalException("SHA-256 is not available", e);
        }
    }

    /**
     * Выданный токен. {@link #toString()} его не показывает.
     *
     * @param token токен для ссылки {@code ?start=}
     * @param expiresAt до какого момента он действует
     */
    record Issued(String token, Instant expiresAt) {

        @Override
        public String toString() {
            return "Issued[token=<hidden>, expiresAt=" + expiresAt + "]";
        }
    }

    private record Consumed(UUID accountId, Instant expiresAt) {}
}
