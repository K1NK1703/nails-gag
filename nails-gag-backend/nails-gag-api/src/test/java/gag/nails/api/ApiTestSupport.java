package gag.nails.api;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.clients.DocumentVersionService;
import gag.nails.clients.model.DocumentKind;
import gag.nails.common.actor.Actor;
import gag.nails.identity.AccessAdministration;
import gag.nails.identity.LoginFlow;
import gag.nails.identity.Sessions;
import gag.nails.identity.command.CompleteRegistration;
import gag.nails.identity.model.AuthorizationResponse;
import gag.nails.identity.model.ClientInfo;
import gag.nails.identity.model.LoginPurpose;
import gag.nails.identity.model.Provider;
import gag.nails.identity.model.RegistrationRequired;
import gag.nails.identity.model.SignedIn;
import gag.nails.identity.testing.FakeProfile;
import gag.nails.identity.testing.FakeProvider;
import gag.nails.testsupport.MutableClock;
import java.util.Objects;
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

    /** Cookie сессии. */
    public static final String SESSION_COOKIE = "ng_session";

    /** {@code subject} разработчика из {@code bootstrap-developers} в {@link ApiTest}. */
    public static final String DEVELOPER_SUBJECT = "api-developer";

    /** Счётчик телефонов: случайное начало, чтобы прогоны на одной базе не сталкивались. */
    private static final AtomicLong PHONES =
            new AtomicLong(ThreadLocalRandom.current().nextLong(900_000_000L));

    /** Телефон разработчика: один на прогон, чтобы повторный вход не менял его способ входа. */
    private static final String DEVELOPER_PHONE = uniquePhone();

    private static final ClientInfo CLIENT_INFO = new ClientInfo("127.0.0.1", "ApiTest");

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

    @Autowired
    private LoginFlow loginFlow;

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
            headers.set("Origin", ORIGIN);
        });
        return spec;
    }

    /** Запрос от имени вошедшего: cookie {@code ng_session}. */
    protected static <S extends RequestHeadersSpec<?>> S as(Session session, S spec) {
        spec.cookie(SESSION_COOKIE, session.cookie());
        return spec;
    }

    /**
     * Вход человека с профилем подставного провайдера; новый человек регистрируется с телефоном профиля и
     * согласием на действующую политику. Сейчас идёт через {@link LoginFlow} модуля, пока у слоя нет адресов
     * входа.
     */
    protected Session signIn(FakeProfile profile) {
        var start = loginFlow.start(Provider.FAKE, LoginPurpose.LOGIN, null);
        var code = fakeProvider.issueCode(profile);
        var result = loginFlow.complete(
                Provider.FAKE,
                new AuthorizationResponse(code, start.state().state(), null),
                start.state(),
                CLIENT_INFO);
        if (result instanceof RegistrationRequired required) {
            var policy = documents.current(DocumentKind.POLICY).version();
            var name = Objects.requireNonNullElse(profile.displayName(), "Без имени");
            var phone = Objects.requireNonNullElseGet(profile.phone(), ApiTestSupport::uniquePhone);
            result = loginFlow.completeRegistration(
                    required.ticket(), new CompleteRegistration(name, phone, policy), CLIENT_INFO);
        }
        assertThat(result).isInstanceOf(SignedIn.class);
        var signedIn = (SignedIn) result;
        return new Session(signedIn.sessionToken(), signedIn.accountId());
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

    /** Cookie из {@code Set-Cookie} ответа со значением и атрибутами; её отсутствие — провал теста. */
    protected static ResponseCookie cookie(ExchangeResult result, String name) {
        var cookie = result.getResponseCookies().getFirst(name);
        assertThat(cookie).as("Set-Cookie " + name).isNotNull();
        return cookie;
    }
}
