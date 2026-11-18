package gag.nails.notifications.internal.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.actor.Actor;
import gag.nails.identity.error.AccountNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Ссылка привязки бота из кабинета (спека §3.6): {@code https://t.me/<бот>?start=<токен>}, токен — 43 символа
 * base64url, в базе — его SHA-256, живёт 10 минут, новая ссылка заменяет прежнюю. Без бота привязка недоступна — это
 * проверяет {@code MyChannelsTest} (бот в его контексте не настроен).
 */
class TelegramLinkTest extends TelegramTestSupport {

    @Test
    void linkLeadsToTheBotWithOneTimeToken() throws Exception {
        var anna = account();

        var link = preferences.createTelegramLink(anna);

        assertThat(link.url().toString()).matches("https://t\\.me/" + BOT + "\\?start=[A-Za-z0-9_-]{43}");
        assertThat(link.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));
        var token = link.url().getQuery().substring("start=".length());
        assertThat(jdbc.sql("select token_hash from notifications.link_token where account_id = ?")
                        .param(anna.accountId())
                        .query(byte[].class)
                        .single())
                .isEqualTo(sha256(token));
        assertThat(preferences.mine(anna).telegramAvailable()).isTrue();
    }

    @Test
    void deletedAccountGetsNoLink() {
        var gone = Actor.of(UUID.randomUUID(), null, Map.of());

        assertThatThrownBy(() -> preferences.createTelegramLink(gone)).isInstanceOf(AccountNotFoundException.class);
        assertThat(jdbc.sql("select count(*) from notifications.link_token where account_id = ?")
                        .param(gone.accountId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void newLinkReplacesThePreviousOne() {
        var anna = account();
        var chat = chat();
        var first = linkToken(anna);
        var second = linkToken(anna);

        assertThat(second).isNotEqualTo(first);
        assertThat(linkTokens(anna)).isOne();

        updates.handle(privateMessage(chat, "/start " + first));
        assertThat(chatOf(anna)).isEmpty();
        updates.handle(privateMessage(chat, "/start " + second));
        assertThat(chatOf(anna)).contains(chat);
        assertThat(replies(chat)).containsExactly(EXPIRED, LINKED);
    }

    @Test
    void linksOfDifferentAccountsAreIndependent() {
        var anna = account();
        var vera = account();
        var annaChat = chat();
        var veraChat = chat();
        var annaToken = linkToken(anna);
        var veraToken = linkToken(vera);

        updates.handle(privateMessage(veraChat, "/start " + veraToken));
        updates.handle(privateMessage(annaChat, "/start " + annaToken));

        assertThat(chatOf(anna)).contains(annaChat);
        assertThat(chatOf(vera)).contains(veraChat);
    }

    @Test
    void unlinkIsIdempotent() {
        var anna = account();
        var chat = chat();
        updates.handle(privateMessage(chat, "/start " + linkToken(anna)));
        assertThat(preferences.mine(anna).telegramLinked()).isTrue();

        preferences.unlinkTelegram(anna);
        preferences.unlinkTelegram(anna);

        assertThat(preferences.mine(anna).telegramLinked()).isFalse();
        assertThat(accountOf(chat)).isEmpty();
    }

    private static byte[] sha256(String token) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
    }
}
