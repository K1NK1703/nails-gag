package gag.nails.api.internal.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Ссылка привязки Telegram по HTTP при настроенном боте (спецификация уведомлений, §3.6, §4). Бот задан свойствами
 * этого контекста; приём обновлений в тестах не запускается (задачи модуля выключены), Bot API не вызывается — ссылку
 * модуль выдаёт без обращения к Telegram. Без бота адрес отвечает {@code 409} — это проверяет
 * {@link NotificationsApiTest}.
 */
@ApiTest
@TestPropertySource(
        properties = {
            "nails-gag.notifications.telegram.bot-token=4242:api-test-token",
            "nails-gag.notifications.telegram.bot-username=" + TelegramLinkApiTest.BOT
        })
class TelegramLinkApiTest extends ApiTestSupport {

    static final String BOT = "nails_gag_bot";

    @Test
    void linkLeadsToTheBotWithOneTimeToken() {
        var anna = signInClient();

        var link = Objects.requireNonNull(as(anna, csrf(client.post().uri("/api/notifications/me/telegram-link")))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody());

        assertThat(link.propertyNames()).containsExactlyInAnyOrder("url", "expiresAt");
        assertThat(field(link, "url").asString()).matches("https://t\\.me/" + BOT + "\\?start=[A-Za-z0-9_-]{43}");
        assertThat(Instant.parse(field(link, "expiresAt").asString()))
                .isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));
        var mine = json(as(anna, client.get().uri("/api/notifications/me")), HttpStatus.OK);
        assertThat(field(mine, "telegramAvailable").asBoolean()).isTrue();
        assertThat(field(mine, "telegramLinked").asBoolean()).isFalse();
    }
}
