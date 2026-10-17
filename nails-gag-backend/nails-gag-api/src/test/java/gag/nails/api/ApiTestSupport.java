package gag.nails.api;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.clients.DocumentVersionService;
import gag.nails.clients.model.DocumentKind;
import gag.nails.common.actor.Actor;
import gag.nails.identity.AccessAdministration;
import gag.nails.identity.Sessions;
import gag.nails.identity.command.CompleteRegistration;
import gag.nails.identity.model.LoginPurpose;
import gag.nails.identity.testing.FakeProfile;
import gag.nails.identity.testing.FakeProvider;
import gag.nails.testsupport.MutableClock;
import java.net.URI;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.servlet.client.ExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.RequestHeadersSpec;

/** Общая обвязка HTTP-тестов {@code api}: клиент, сервисы модулей для подготовки данных, уникальные люди. */
public abstract class ApiTestSupport {

    /** Служебный заголовок, который ждёт защита от CSRF. */
    public static final String REQUESTED_WITH = "nails.gag";

    /** Cookie сессии. */
    public static final String SESSION_COOKIE = "ng_session";

    /** Cookie этапа входа (зашифрованный {@code LoginState}). */
    public static final String LOGIN_COOKIE = "ng_login";

    /** Cookie билета регистрации или привязки. */
    public static final String TICKET_COOKIE = "ng_ticket";

    /**
     * Origin сайта — {@code site-origin} в {@link ApiTest}: его ждёт защита от CSRF, на него обратный вызов отправляет
     * браузер.
     */
    public static final String SITE = "http://localhost";

    /** {@code subject} разработчика из {@code bootstrap-developers} в {@link ApiTest}. */
    public static final String DEVELOPER_SUBJECT = "api-developer";

    /** Счётчик телефонов: случайное начало, чтобы прогоны на одной базе не сталкивались. */
    private static final AtomicLong PHONES =
            new AtomicLong(ThreadLocalRandom.current().nextLong(900_000_000L));

    /**
     * Счётчик дней для слотов: случайное начало в 60–2000 днях от часов, чтобы прогоны на одной базе не сталкивались
     * слотами, а день каждого теста был свободен.
     */
    private static final AtomicLong DAYS =
            new AtomicLong(ThreadLocalRandom.current().nextLong(60, 2000));

    /** Телефон разработчика: один на прогон, чтобы повторный вход не менял его способ входа. */
    private static final String DEVELOPER_PHONE = uniquePhone();

    /**
     * Счётчик адресов клиента для запросов входа: у каждого запроса своя сеть IPv6 /64 из документационного
     * диапазона {@code 2001:db8::/32}, поэтому вход в тестах не расходует корзины лимитов, которые проверяют сами
     * тесты (там лимит входа — три запроса в минуту с адреса).
     */
    private static final AtomicInteger IPS = new AtomicInteger();

    /** {@code state} в адресе подставного провайдера: {@code urn:nails-gag:fake?state=…}. */
    private static final Pattern STATE = Pattern.compile("[?&]state=([^&]+)");

    @Autowired
    protected RestTestClient client;

    @Autowired
    protected FakeProvider fakeProvider;

    @Autowired
    protected AccessAdministration access;

    @Autowired
    protected DocumentVersionService documents;

    @Autowired
    protected Sessions sessions;

    @Autowired
    protected MutableClock clock;

    /**
     * Вошедший: {@code cookie} — значение cookie {@code ng_session} (токен сессии), {@code accountId} — его
     * аккаунт.
     */
    public record Session(String cookie, UUID accountId) {

        /** Токен в отчёты тестов не попадает. */
        @Override
        public String toString() {
            return "Session[accountId=" + accountId + "]";
        }
    }

    /**
     * Заголовки, без которых изменяющий запрос не пройдёт защиту от CSRF. Спецификация запроса — изменяемый
     * построитель, поэтому возвращается она же: тип сохраняется и для {@code post()}, и для {@code delete()}.
     */
    protected static <S extends RequestHeadersSpec<?>> S csrf(S spec) {
        spec.headers(headers -> {
            headers.set("X-Requested-With", REQUESTED_WITH);
            headers.set("Origin", SITE);
        });
        return spec;
    }

    /** Запрос от имени вошедшего: cookie {@code ng_session}. */
    protected static <S extends RequestHeadersSpec<?>> S as(Session session, S spec) {
        spec.cookie(SESSION_COOKIE, session.cookie());
        return spec;
    }

    /**
     * Начатый вход: {@code state} из адреса провайдера и значение cookie {@code ng_login}.
     *
     * @param cookie зашифрованное состояние, как его вернёт браузер в обратном вызове
     */
    public record LoginStarted(String state, String cookie) {}

    /**
     * Вход человека с профилем подставного провайдера — по HTTP, как браузер: {@code start} → {@code code} →
     * обратный вызов; новый человек регистрируется с телефоном профиля и согласием на действующую политику.
     */
    protected Session signIn(FakeProfile profile) {
        var started = startLogin(LoginPurpose.LOGIN, null);
        var callback = callback(started, fakeCode(profile), null);
        var location = location(callback);
        ExchangeResult signedIn;
        if (location.equals(URI.create(SITE + "/register"))) {
            var policy = documents.current(DocumentKind.POLICY).version();
            var name = Objects.requireNonNullElse(profile.displayName(), "Без имени");
            var phone = Objects.requireNonNullElseGet(profile.phone(), ApiTestSupport::uniquePhone);
            signedIn = register(
                            cookie(callback, TICKET_COOKIE).getValue(), new CompleteRegistration(name, phone, policy))
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .returnResult();
        } else {
            assertThat(location).isEqualTo(URI.create(SITE + "/cabinet"));
            signedIn = callback;
        }
        var token = cookie(signedIn, SESSION_COOKIE).getValue();
        var account = sessions.authenticate(token).orElseThrow().account();
        return new Session(token, account.id());
    }

    /**
     * {@code POST /api/auth/fake/start}: адрес провайдера и cookie этапа входа; привязка — от имени
     * {@code session}.
     */
    protected LoginStarted startLogin(LoginPurpose purpose, @Nullable Session session) {
        var spec = csrf(client.post().uri("/api/auth/fake/start"))
                .header("X-Forwarded-For", nextIp())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("purpose", purpose));
        if (session != null) {
            as(session, spec);
        }
        var result = spec.exchange()
                .expectStatus()
                .isOk()
                .expectBody(LoginRedirect.class)
                .returnResult();
        var redirect = Objects.requireNonNull(result.getResponseBody());
        var matcher = STATE.matcher(redirect.authorizationUri());
        assertThat(matcher.find()).as("state in " + redirect.authorizationUri()).isTrue();
        return new LoginStarted(matcher.group(1), cookie(result, LOGIN_COOKIE).getValue());
    }

    /** {@code POST /api/auth/fake/code}: одноразовый код подставного провайдера для профиля. */
    protected String fakeCode(FakeProfile profile) {
        var code = csrf(client.post().uri("/api/auth/fake/code"))
                .header("X-Forwarded-For", nextIp())
                .contentType(MediaType.APPLICATION_JSON)
                .body(profile)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(FakeCode.class)
                .returnResult()
                .getResponseBody();
        return Objects.requireNonNull(code).code();
    }

    /**
     * Обратный вызов провайдера с кодом и {@code state} начатого входа, как его делает браузер: с cookie
     * {@code ng_login} и, если задана, cookie сессии. Ответ — всегда {@code 303}.
     */
    protected ExchangeResult callback(LoginStarted started, String code, @Nullable Session session) {
        var spec = client.get()
                .uri("/api/auth/fake/callback?code={code}&state={state}", code, started.state())
                .header("X-Forwarded-For", nextIp())
                .cookie(LOGIN_COOKIE, started.cookie());
        if (session != null) {
            as(session, spec);
        }
        return spec.exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER)
                .expectBody()
                .returnResult();
    }

    /** {@code POST /api/auth/registration} с билетом из cookie {@code ng_ticket}. */
    protected RestTestClient.ResponseSpec register(String ticket, CompleteRegistration command) {
        return csrf(client.post().uri("/api/auth/registration"))
                .header("X-Forwarded-For", nextIp())
                .cookie(TICKET_COOKIE, ticket)
                .contentType(MediaType.APPLICATION_JSON)
                .body(command)
                .exchange();
    }

    /** Куда ответ отправляет браузер. */
    protected static URI location(ExchangeResult result) {
        var location = result.getResponseHeaders().getLocation();
        assertThat(location).as("Location").isNotNull();
        return location;
    }

    /** Адрес клиента для {@code X-Forwarded-For}: каждый раз новая сеть /64, своя корзина лимита входа. */
    protected static String nextIp() {
        return "2001:db8:a:%x::1".formatted(IPS.incrementAndGet());
    }

    /** Разработчик из {@code bootstrap-developers}: один аккаунт на весь прогон. */
    protected Session signInDeveloper() {
        return signIn(new FakeProfile(DEVELOPER_SUBJECT, DEVELOPER_PHONE, null, "Разработчик", null));
    }

    /** Новый человек со встроенной ролью «Мастер», выданной разработчиком. */
    protected Session signInMaster() {
        var master = signInClient();
        var developer = actor(signInDeveloper());
        var role = access.roles(developer).stream()
                .filter(candidate -> candidate.code().equals("MASTER"))
                .findFirst()
                .orElseThrow();
        access.assignRole(master.accountId(), role.id(), developer);
        return master;
    }

    /** Новый человек без ролей: только своя карточка клиента. */
    protected Session signInClient() {
        var subject = uniqueSubject();
        return signIn(new FakeProfile(subject, uniquePhone(), null, "Клиент " + subject, null));
    }

    /** Актор вошедшего, как его видит модуль на этом запросе. */
    protected Actor actor(Session session) {
        return sessions.authenticate(session.cookie()).orElseThrow().actor();
    }

    /** {@code subject} нового человека у подставного провайдера. */
    protected static String uniqueSubject() {
        return "api-" + UUID.randomUUID();
    }

    /** Телефон, которого ещё нет в базе этого прогона: {@code +79} и девять цифр. */
    protected static String uniquePhone() {
        return "+79%09d".formatted(PHONES.getAndIncrement() % 1_000_000_000L);
    }

    /** День, которого ещё не выдавал этот прогон: на нём нет слотов соседних тестов. */
    protected LocalDate freshDay() {
        return LocalDate.now(clock).plusDays(DAYS.getAndIncrement());
    }

    /** Cookie из {@code Set-Cookie} ответа со значением и атрибутами; её отсутствие — провал теста. */
    protected static ResponseCookie cookie(ExchangeResult result, String name) {
        var cookie = result.getResponseCookies().getFirst(name);
        assertThat(cookie).as("Set-Cookie " + name).isNotNull();
        return cookie;
    }

    /** Тело ответа {@code start}. */
    record LoginRedirect(String authorizationUri) {}

    /** Тело ответа {@code /api/auth/fake/code}. */
    record FakeCode(String code) {}
}
