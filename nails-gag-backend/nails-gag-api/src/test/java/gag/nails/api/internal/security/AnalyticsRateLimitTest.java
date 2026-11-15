package gag.nails.api.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

/**
 * Приём событий счётчика ({@code POST /api/analytics/events}) — в своей корзине по IP, 120 событий в минуту по
 * умолчанию (спецификация счётчика, §4), вместо остальных корзин: посетитель, листающий сайт, не расходует общую
 * анонимную корзину (вход, запись), а вошедший — корзину аккаунта. Настройки и контекст — как у
 * {@link RateLimitTest}: маленькие анонимная корзина и корзина аккаунта, лимит приёма — по умолчанию. У каждого теста
 * свои IP. Тело события неизвестного вида: запрос засчитан в корзину, а в базу ничего не пишется.
 */
@ApiTest
@TestPropertySource(
        properties = {
            RateLimitTest.AUTH_PER_MINUTE,
            RateLimitTest.ANONYMOUS_PER_MINUTE,
            RateLimitTest.AUTHENTICATED_PER_MINUTE
        })
class AnalyticsRateLimitTest extends ApiTestSupport {

    /** Лимит приёма по умолчанию: {@code nails-gag.api.rate-limits.analytics-per-minute}. */
    private static final int EVENTS_PER_MINUTE = 120;

    private static final String IGNORED_EVENT = "{\"name\":\"unknown\"}";

    @Test
    void event121InAMinuteFromOneIpIsLimited() {
        for (var i = 0; i < EVENTS_PER_MINUTE; i++) {
            event("198.51.100.60", null).expectStatus().isNoContent();
        }

        event("198.51.100.60", null)
                .expectStatus()
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader()
                .value(
                        HttpHeaders.RETRY_AFTER,
                        retryAfter -> assertThat(Long.parseLong(retryAfter)).isPositive())
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.rate_limited");
        // Корзина по IP: с другого адреса события идут.
        event("198.51.100.61", null).expectStatus().isNoContent();
    }

    /** IPv6 — по сети /64: смена адреса внутри своей сети лимит не обходит. */
    @Test
    void ipv6AddressesOfOneSlash64ShareTheBucket() {
        for (var i = 0; i < EVENTS_PER_MINUTE; i++) {
            event("2001:db8:60:1::%x".formatted(i + 1), null).expectStatus().isNoContent();
        }

        event("2001:db8:60:1:ffff::1", null).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        event("2001:db8:60:2::1", null).expectStatus().isNoContent();
    }

    @Test
    void eventsDoNotSpendAnonymousBucket() {
        for (var i = 0; i < EVENTS_PER_MINUTE; i++) {
            event("198.51.100.62", null).expectStatus().isNoContent();
        }

        for (var i = 0; i < RateLimitTest.ANONYMOUS; i++) {
            anonymous("198.51.100.62").expectStatus().isOk();
        }
        anonymous("198.51.100.62").expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // И наоборот: пустая анонимная корзина не мешает приёму.
        event("198.51.100.63", null).expectStatus().isNoContent();
        for (var i = 0; i < RateLimitTest.ANONYMOUS; i++) {
            anonymous("198.51.100.63").expectStatus().isOk();
        }
        event("198.51.100.63", null).expectStatus().isNoContent();
    }

    /** Вошедший шлёт события из той же корзины по IP, корзина аккаунта на них не тратится. */
    @Test
    void signedInEventsUseIpBucket() {
        var session = signInClient();

        for (var i = 0; i < EVENTS_PER_MINUTE; i++) {
            event("198.51.100.64", session).expectStatus().isNoContent();
        }

        event("198.51.100.64", session).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        event("198.51.100.64", null).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        as(session, client.get().uri("/api/test/whoami"))
                .header("X-Forwarded-For", "198.51.100.64")
                .exchange()
                .expectStatus()
                .isOk();
    }

    /** Своя корзина — только у {@code POST} приёма: другой метод на том же адресе идёт в общую корзину. */
    @Test
    void otherMethodsOnEventsAddressSpendAnonymousBucket() {
        for (var i = 0; i < RateLimitTest.ANONYMOUS; i++) {
            client.get()
                    .uri("/api/analytics/events")
                    .header("X-Forwarded-For", "198.51.100.65")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        }

        anonymous("198.51.100.65").expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        event("198.51.100.65", null).expectStatus().isNoContent();
    }

    private ResponseSpec event(String ip, @Nullable Session session) {
        var spec = client.post()
                .uri("/api/analytics/events")
                .header(HttpHeaders.ORIGIN, SITE)
                .header("X-Forwarded-For", ip)
                .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 (X11; Linux x86_64) Firefox/140.0")
                .contentType(MediaType.APPLICATION_JSON)
                .body(IGNORED_EVENT);
        if (session != null) {
            as(session, spec);
        }
        return spec.exchange();
    }

    private ResponseSpec anonymous(String ip) {
        return client.get().uri("/api/test/maybe").header("X-Forwarded-For", ip).exchange();
    }
}
