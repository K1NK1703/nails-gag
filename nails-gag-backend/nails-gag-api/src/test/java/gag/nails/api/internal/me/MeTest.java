package gag.nails.api.internal.me;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import gag.nails.identity.model.LoginPurpose;
import gag.nails.identity.testing.FakeProfile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** Профиль вошедшего (спецификация API, §7.1): аккаунт с уровнями по разделам и своя карточка клиента. */
@ApiTest
class MeTest extends ApiTestSupport {

    @Test
    void clientSeesAccountAndOwnCard() {
        var subject = uniqueSubject();
        var name = "Клиент " + subject;
        var session = signIn(new FakeProfile(subject, uniquePhone(), null, name, null));
        var clientId = actor(session).clientId();

        var body = as(session, client.get().uri("/api/me"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.account.id")
                .isEqualTo(session.accountId().toString())
                .jsonPath("$.account.clientId")
                .isEqualTo(String.valueOf(clientId))
                .jsonPath("$.client.id")
                .isEqualTo(String.valueOf(clientId))
                .jsonPath("$.client.firstName")
                .isEqualTo(name)
                .returnResult()
                .getResponseBody();

        // Прав в админке нет: модуль перечисляет разделы, но ни в одном уровень не выше NONE.
        Map<String, String> access =
                JsonPath.read(new String(Objects.requireNonNull(body), StandardCharsets.UTF_8), "$.account.access");
        assertThat(access.values()).containsOnly("NONE");
    }

    @Test
    void developerSeesSectionLevels() {
        var developer = signInDeveloper();

        as(developer, client.get().uri("/api/me"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.account.id")
                .isEqualTo(developer.accountId().toString())
                .jsonPath("$.account.access.ACCESS")
                .isEqualTo("WRITE")
                .jsonPath("$.account.access.FINANCE")
                .isEqualTo("READ");
    }

    @Test
    void anonymousIsUnauthenticated() {
        client.get()
                .uri("/api/me")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.unauthenticated");
    }

    @Test
    void deleteRemovesAccountAndClearsCookie() {
        var subject = uniqueSubject();
        var profile = new FakeProfile(subject, uniquePhone(), null, "Клиент " + subject, null);
        var session = signIn(profile);

        var result = as(session, csrf(client.delete().uri("/api/me")))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody()
                .returnResult();

        var cleared = cookie(result, SESSION_COOKIE);
        assertThat(cleared.getValue()).isEmpty();
        assertThat(cleared.getMaxAge()).isEqualTo(Duration.ZERO);
        assertThat(cleared.getPath()).isEqualTo("/api");
        as(session, client.get().uri("/api/me")).exchange().expectStatus().isUnauthorized();
        // Аккаунта больше нет: тот же способ входа ведёт на регистрацию заново.
        var again = callback(startLogin(LoginPurpose.LOGIN, null), fakeCode(profile), null);
        assertThat(location(again)).isEqualTo(URI.create(SITE + "/register"));
    }
}
