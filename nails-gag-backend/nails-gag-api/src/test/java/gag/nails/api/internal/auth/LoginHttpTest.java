package gag.nails.api.internal.auth;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import gag.nails.clients.ClientService;
import gag.nails.clients.model.DocumentKind;
import gag.nails.common.actor.Actor;
import gag.nails.identity.command.CompleteRegistration;
import gag.nails.identity.model.LoginOutcome;
import gag.nails.identity.model.LoginPurpose;
import gag.nails.identity.model.Provider;
import gag.nails.identity.testing.FakeProfile;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;

@ApiTest
class LoginHttpTest extends ApiTestSupport {

    private static final URI CABINET = URI.create(SITE + "/cabinet");
    private static final URI REGISTER = URI.create(SITE + "/register");
    private static final URI LINK_CONFIRM = URI.create(SITE + "/cabinet/account?link=confirm");
    private static final URI LINKED = URI.create(SITE + "/cabinet/account?link=done");
    private static final URI FAILED = URI.create(SITE + "/login?error=failed");
    private static final URI BLOCKED = URI.create(SITE + "/login?error=blocked");
    private static final URI PROVIDER = URI.create(SITE + "/login?error=provider");

    @Autowired
    private ClientService clients;

    @Test
    void startReturnsProviderUriAndSetsEncryptedStateCookie() {
        var authorizationUri = new AtomicReference<String>();
        var result = csrf(client.post().uri("/api/auth/fake/start"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("purpose", LoginPurpose.LOGIN))
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.authorizationUri")
                .value(String.class, authorizationUri::set)
                .returnResult();

        assertThat(authorizationUri.get()).startsWith("urn:nails-gag:fake?state=");
        var state = authorizationUri.get().substring("urn:nails-gag:fake?state=".length());
        var login = cookie(result, LOGIN_COOKIE);
        assertThat(login.isHttpOnly()).isTrue();
        assertThat(login.isSecure()).isTrue();
        assertThat(login.getSameSite()).isEqualTo("Lax");
        assertThat(login.getPath()).isEqualTo("/api/auth");
        assertThat(login.getMaxAge()).isEqualTo(Duration.ofMinutes(10));
        assertThat(state).isNotBlank();
        assertThat(login.getValue()).isNotBlank().doesNotContain(state);
    }

    @Test
    void knownPersonCallbackSignsInAndRedirectsToCabinet() {
        var profile = person();
        var registered = signIn(profile);

        var started = startLogin(LoginPurpose.LOGIN, null);
        var result = callback(started, fakeCode(profile), null);

        assertThat(location(result)).isEqualTo(CABINET);
        assertThat(result.getResponseHeaders().getCacheControl()).isEqualTo("no-store");
        var session = cookie(result, SESSION_COOKIE);
        assertThat(session.isHttpOnly()).isTrue();
        assertThat(session.isSecure()).isTrue();
        assertThat(session.getSameSite()).isEqualTo("Lax");
        assertThat(session.getPath()).isEqualTo("/api");
        assertThat(session.getMaxAge()).isEqualTo(Duration.ofDays(90));
        assertThat(sessions.authenticate(session.getValue())
                        .orElseThrow()
                        .actor()
                        .accountId())
                .isEqualTo(registered.accountId());
        assertCleared(cookie(result, LOGIN_COOKIE), "/api/auth");
    }

    @Test
    void newPersonCallbackRedirectsToRegistrationWithTicketCookie() {
        var result = callback(startLogin(LoginPurpose.LOGIN, null), fakeCode(person()), null);

        assertThat(location(result)).isEqualTo(REGISTER);
        var ticket = cookie(result, TICKET_COOKIE);
        assertThat(ticket.getValue()).isNotBlank();
        assertThat(ticket.isHttpOnly()).isTrue();
        assertThat(ticket.isSecure()).isTrue();
        assertThat(ticket.getSameSite()).isEqualTo("Lax");
        assertThat(ticket.getPath()).isEqualTo("/api/auth");
        assertThat(ticket.getMaxAge()).isEqualTo(Duration.ofMinutes(30));
        assertThat(location(result).toString()).doesNotContain(ticket.getValue());
        assertThat(result.getResponseCookies().getFirst(SESSION_COOKIE)).isNull();
        assertCleared(cookie(result, LOGIN_COOKIE), "/api/auth");
    }

    @Test
    void registrationPrefillIsReadableByTicket() {
        var profile = person();
        var ticket = registrationTicket(profile);

        client.get()
                .uri("/api/auth/registration")
                .cookie(TICKET_COOKIE, ticket)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.displayName")
                .isEqualTo(profile.displayName())
                .jsonPath("$.phone")
                .isEqualTo(profile.phone())
                .jsonPath("$.phoneVerified")
                .isEqualTo(true);
    }

    @Test
    void registrationSetsSessionAndClearsTicket() {
        var profile = person();
        var ticket = registrationTicket(profile);

        var result = register(ticket, registration(profile, currentPolicy()))
                .expectStatus()
                .isNoContent()
                .expectBody()
                .returnResult();

        var session = cookie(result, SESSION_COOKIE);
        assertThat(session.getPath()).isEqualTo("/api");
        assertThat(session.getMaxAge()).isEqualTo(Duration.ofDays(90));
        var actor = sessions.authenticate(session.getValue()).orElseThrow().actor();
        assertThat(actor.clientId()).isNotNull();
        assertCleared(cookie(result, TICKET_COOKIE), "/api/auth");
        // Билет потреблён: повтор с той же cookie — просрочен.
        register(ticket, registration(profile, currentPolicy()))
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("identity.registration_expired");
    }

    @Test
    void registrationWithOutdatedPolicyKeepsTicket() {
        var profile = person();
        var ticket = registrationTicket(profile);

        var result = register(ticket, registration(profile, "no-such-version"))
                .expectStatus()
                .isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("clients.consent_version_outdated")
                .returnResult();
        assertThat(result.getResponseCookies().getFirst(TICKET_COOKIE)).isNull();

        // Ошибка формы билет тоже не трогает.
        var invalid = register(ticket, new CompleteRegistration("", "", ""))
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.validation")
                .returnResult();
        assertThat(invalid.getResponseCookies().getFirst(TICKET_COOKIE)).isNull();

        register(ticket, registration(profile, currentPolicy())).expectStatus().isNoContent();
    }

    @Test
    void registrationWithoutTicketIsExpired() {
        csrf(client.post().uri("/api/auth/registration"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(registration(person(), currentPolicy()))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("identity.registration_expired");
        client.get()
                .uri("/api/auth/registration")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("identity.registration_expired");
        client.get()
                .uri("/api/auth/registration")
                .cookie(TICKET_COOKIE, "unknown-ticket")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("identity.registration_expired");
    }

    @Test
    void missingCodeFailsWithoutCallingProvider() {
        var started = startLogin(LoginPurpose.LOGIN, null);
        // Провайдер упал бы, если бы до него дошло: тогда исход был бы error=provider.
        fakeProvider.failNextExchange();

        var result = client.get()
                .uri("/api/auth/fake/callback?error=access_denied&state={state}", started.state())
                .cookie(LOGIN_COOKIE, started.cookie())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER)
                .expectBody()
                .returnResult();

        assertThat(location(result)).isEqualTo(FAILED);
        assertCleared(cookie(result, LOGIN_COOKIE), "/api/auth");
        // Без state — тоже отказ до провайдера.
        assertThat(location(client.get()
                        .uri("/api/auth/fake/callback?code={code}", fakeCode(person()))
                        .cookie(LOGIN_COOKIE, started.cookie())
                        .exchange()
                        .expectStatus()
                        .isEqualTo(HttpStatus.SEE_OTHER)
                        .expectBody()
                        .returnResult()))
                .isEqualTo(FAILED);
        // Сбой провайдера ещё взведён: до него дошёл только следующий полный вход.
        assertThat(location(callback(startLogin(LoginPurpose.LOGIN, null), fakeCode(person()), null)))
                .isEqualTo(PROVIDER);
    }

    @Test
    void foreignStateFails() {
        var started = startLogin(LoginPurpose.LOGIN, null);
        var ip = nextIp();

        var result = client.get()
                .uri("/api/auth/fake/callback?code={code}&state={state}", fakeCode(person()), "foreign-state")
                .header("X-Forwarded-For", ip)
                .cookie(LOGIN_COOKIE, started.cookie())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER)
                .expectBody()
                .returnResult();

        assertThat(location(result)).isEqualTo(FAILED);
        assertCleared(cookie(result, LOGIN_COOKIE), "/api/auth");
        assertThat(access.loginJournal(500, Actor.developer())).anySatisfy(event -> {
            assertThat(event.ip()).isEqualTo(ip);
            assertThat(event.provider()).isEqualTo(Provider.FAKE);
            assertThat(event.outcome()).isEqualTo(LoginOutcome.FAILED);
        });
    }

    @Test
    void tamperedStateCookieFails() {
        var profile = person();
        var started = startLogin(LoginPurpose.LOGIN, null);
        var value = started.cookie();
        var last = value.charAt(value.length() - 1);
        var tampered = value.substring(0, value.length() - 1) + (last == 'A' ? 'B' : 'A');

        for (var cookie : new String[] {tampered, "not-a-state", ""}) {
            var result = callback(new LoginStarted(started.state(), cookie), fakeCode(profile), null);
            assertThat(location(result)).as(cookie).isEqualTo(FAILED);
            assertCleared(cookie(result, LOGIN_COOKIE), "/api/auth");
            assertThat(result.getResponseCookies().getFirst(TICKET_COOKIE)).isNull();
        }
        // Без cookie вовсе — то же самое.
        var result = client.get()
                .uri("/api/auth/fake/callback?code={code}&state={state}", fakeCode(profile), started.state())
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SEE_OTHER)
                .expectBody()
                .returnResult();
        assertThat(location(result)).isEqualTo(FAILED);
    }

    @Test
    void linkStartWithoutSessionIsUnauthenticated() {
        csrf(client.post().uri("/api/auth/fake/start"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("purpose", LoginPurpose.LINK))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.unauthenticated");
    }

    @Test
    void linkCallbackWithAnotherSessionFails() {
        var first = signInClient();
        var second = signInClient();
        var profile = person();

        var started = startLogin(LoginPurpose.LINK, first);
        assertThat(location(callback(started, fakeCode(profile), second))).isEqualTo(FAILED);

        var again = startLogin(LoginPurpose.LINK, first);
        assertThat(location(callback(again, fakeCode(profile), null))).isEqualTo(FAILED);
    }

    @Test
    void linkOfOwnMethodRedirectsToLinked() {
        var profile = person();
        var session = signIn(profile);

        var own = callback(startLogin(LoginPurpose.LINK, session), fakeCode(profile), session);
        assertThat(location(own)).isEqualTo(LINKED);
        assertCleared(cookie(own, LOGIN_COOKIE), "/api/auth");

        // Ничей способ входа — тоже сразу привязан, и потом по нему входят в тот же аккаунт.
        var unowned = new FakeProfile(uniqueSubject(), null, null, "Второй способ", null);
        assertThat(location(callback(startLogin(LoginPurpose.LINK, session), fakeCode(unowned), session)))
                .isEqualTo(LINKED);
        assertThat(signIn(unowned).accountId()).isEqualTo(session.accountId());
    }

    @Test
    void linkOfForeignMethodAsksConfirmationAndConfirmMerges() {
        var first = signInClient();
        var otherProfile = person();
        var second = signIn(otherProfile);

        var result = callback(startLogin(LoginPurpose.LINK, first), fakeCode(otherProfile), first);
        assertThat(location(result)).isEqualTo(LINK_CONFIRM);
        var ticket = cookie(result, TICKET_COOKIE).getValue();

        var confirmed = as(first, csrf(client.post().uri("/api/auth/link/confirm")))
                .cookie(TICKET_COOKIE, ticket)
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody()
                .returnResult();

        assertCleared(cookie(confirmed, TICKET_COOKIE), "/api/auth");
        // Второй аккаунт слит в первый: его сессия больше не действует, а способ входа ведёт в первый.
        as(second, client.get().uri("/api/test/whoami"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
        as(first, client.get().uri("/api/test/whoami"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.accountId")
                .isEqualTo(first.accountId().toString());
        assertThat(signIn(otherProfile).accountId()).isEqualTo(first.accountId());
        // Билет одноразовый.
        as(first, csrf(client.post().uri("/api/auth/link/confirm")))
                .cookie(TICKET_COOKIE, ticket)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("identity.registration_expired");
    }

    @Test
    void blockedPersonIsRedirectedWithBlockedError() {
        var profile = person();
        var session = signIn(profile);
        var clientId = Objects.requireNonNull(actor(session).clientId());
        clients.block(clientId, null, Actor.developer());

        var result = callback(startLogin(LoginPurpose.LOGIN, null), fakeCode(profile), null);

        assertThat(location(result)).isEqualTo(BLOCKED);
        assertThat(result.getResponseCookies().getFirst(SESSION_COOKIE)).isNull();
        assertCleared(cookie(result, LOGIN_COOKIE), "/api/auth");
    }

    @Test
    void providerFailureRedirectsWithProviderError() {
        var started = startLogin(LoginPurpose.LOGIN, null);
        var code = fakeCode(person());
        fakeProvider.failNextExchange();

        var result = callback(started, code, null);

        assertThat(location(result)).isEqualTo(PROVIDER);
        assertCleared(cookie(result, LOGIN_COOKIE), "/api/auth");
    }

    @Test
    void unknownProviderIsNotFound() {
        csrf(client.post().uri("/api/auth/google/start"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("purpose", LoginPurpose.LOGIN))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.not_found");
        client.get()
                .uri("/api/auth/google/callback?code=x&state=y")
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    /** Новый человек с подтверждённым телефоном. */
    private static FakeProfile person() {
        var subject = uniqueSubject();
        return new FakeProfile(subject, uniquePhone(), null, "Клиент " + subject, null);
    }

    /** Новый человек дошёл до экрана регистрации: билет из cookie {@code ng_ticket}. */
    private String registrationTicket(FakeProfile profile) {
        var result = callback(startLogin(LoginPurpose.LOGIN, null), fakeCode(profile), null);
        assertThat(location(result)).isEqualTo(REGISTER);
        return cookie(result, TICKET_COOKIE).getValue();
    }

    private String currentPolicy() {
        return documents.current(DocumentKind.POLICY).version();
    }

    private static CompleteRegistration registration(FakeProfile profile, String policyVersion) {
        return new CompleteRegistration(
                Objects.requireNonNull(profile.displayName()), Objects.requireNonNull(profile.phone()), policyVersion);
    }

    private static void assertCleared(ResponseCookie cookie, String path) {
        assertThat(cookie.getValue()).isEmpty();
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ZERO);
        assertThat(cookie.getPath()).isEqualTo(path);
    }
}
