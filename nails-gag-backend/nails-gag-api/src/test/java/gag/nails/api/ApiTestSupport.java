package gag.nails.api;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.clients.DocumentVersionService;
import gag.nails.identity.AccessAdministration;
import gag.nails.identity.testing.FakeProvider;
import gag.nails.testsupport.MutableClock;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.servlet.client.ExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.RequestHeadersSpec;

/** Общая обвязка HTTP-тестов {@code api}: клиент, сервисы модулей для подготовки данных, уникальные люди. */
public abstract class ApiTestSupport {

    /** Служебный заголовок и origin, которые ждёт защита от CSRF. */
    public static final String REQUESTED_WITH = "nails.gag";

    public static final String ORIGIN = "http://localhost";

    /** Счётчик телефонов: случайное начало, чтобы прогоны на одной базе не сталкивались. */
    private static final AtomicLong PHONES =
            new AtomicLong(ThreadLocalRandom.current().nextLong(900_000_000L));

    @Autowired
    protected RestTestClient client;

    @Autowired
    protected FakeProvider fakeProvider;

    @Autowired
    protected AccessAdministration access;

    @Autowired
    protected DocumentVersionService documents;

    @Autowired
    protected MutableClock clock;

    /**
     * Заголовки, без которых изменяющий запрос не пройдёт защиту от CSRF. Спецификация запроса — изменяемый
     * построитель, поэтому возвращается она же: тип сохраняется и для {@code post()}, и для {@code delete()}.
     */
    protected static <S extends RequestHeadersSpec<?>> S csrf(S spec) {
        spec.headers(headers -> {
            headers.set("X-Requested-With", REQUESTED_WITH);
            headers.set("Origin", ORIGIN);
        });
        return spec;
    }

    /** {@code subject} нового человека у подставного провайдера. */
    protected static String uniqueSubject() {
        return "api-" + UUID.randomUUID();
    }

    /** Телефон, которого ещё нет в базе этого прогона: {@code +79} и девять цифр. */
    protected static String uniquePhone() {
        return "+79%09d".formatted(PHONES.getAndIncrement() % 1_000_000_000L);
    }

    /** Cookie из {@code Set-Cookie} ответа со значением и атрибутами; её отсутствие — провал теста. */
    protected static ResponseCookie cookie(ExchangeResult result, String name) {
        var cookie = result.getResponseCookies().getFirst(name);
        assertThat(cookie).as("Set-Cookie " + name).isNotNull();
        return cookie;
    }
}
