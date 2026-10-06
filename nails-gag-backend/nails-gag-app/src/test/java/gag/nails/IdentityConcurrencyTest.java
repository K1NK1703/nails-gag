package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.clients.ClientQueries;
import gag.nails.clients.ClientService;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.identity.AccessAdministration;
import gag.nails.identity.Accounts;
import gag.nails.identity.AuthorizationResponse;
import gag.nails.identity.ClientInfo;
import gag.nails.identity.CompleteRegistration;
import gag.nails.identity.LoginFlow;
import gag.nails.identity.LoginPurpose;
import gag.nails.identity.LoginResult;
import gag.nails.identity.LoginState;
import gag.nails.identity.Provider;
import gag.nails.identity.RegistrationRequired;
import gag.nails.identity.RoleView;
import gag.nails.identity.Sessions;
import gag.nails.identity.SignedIn;
import gag.nails.identity.error.AccountBlockedException;
import gag.nails.identity.error.LastAccessAdminException;
import gag.nails.identity.testing.FakeProfile;
import gag.nails.identity.testing.FakeProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Параллельные входы и изменения прав с настоящими транзакциями дают согласованный итог (спецификация
 * 4.2, 4.3, 4.5). База одна на все тесты класса, поэтому {@code subject} и телефоны у каждого прогона
 * свои; первый администратор появляется через {@code bootstrap-developers} и при каждом входе получает
 * роль заново, если потерял её в предыдущем тесте.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.NONE,
        properties = {
            "nails-gag.booking.jobs.enabled=false",
            "nails-gag.identity.jobs.enabled=false",
            "nails-gag.identity.fake-provider.enabled=true",
            "nails-gag.identity.bootstrap-developers=FAKE:" + IdentityConcurrencyTest.BOOTSTRAP_SUBJECT
        })
@Import(PostgresTestcontainers.class)
class IdentityConcurrencyTest {

    static final String BOOTSTRAP_SUBJECT = "bootstrap-developer";

    private static final String POLICY_VERSION = "2026-09-25";
    private static final ClientInfo CLIENT = new ClientInfo("203.0.113.7", "JUnit");

    @Autowired
    LoginFlow loginFlow;

    @Autowired
    Sessions sessions;

    @Autowired
    Accounts accounts;

    @Autowired
    AccessAdministration access;

    @Autowired
    FakeProvider fake;

    @Autowired
    ClientService clients;

    @Autowired
    ClientQueries clientQueries;

    @Autowired
    JdbcClient jdbc;

    private final Actor master = Actor.master();

    /**
     * Два первых входа одного человека: оба получают билет регистрации, обе формы отправляются
     * одновременно. {@code completeRegistration} берёт те же advisory-блокировки, что {@code complete},
     * и после них заново сопоставляет профиль: вторая регистрация застаёт способ входа, созданный
     * первой, и завершается входом в тот же аккаунт. Поэтому обе — {@code SignedIn} с одним {@code
     * accountId}, аккаунт и карточка одни.
     */
    @Test
    void twoFirstLoginsOfOneSubjectAtOnceGiveOneAccount() throws Exception {
        var profile = profile("Анна");
        var first = registrationRequired(profile);
        var second = registrationRequired(profile);

        var outcomes = race(() -> register(first, profile), () -> register(second, profile));

        assertThat(outcomes).extracting(Outcome::failure).containsOnlyNulls();
        var signedIn =
                outcomes.stream().map(outcome -> (SignedIn) outcome.result()).toList();
        assertThat(signedIn)
                .extracting(SignedIn::accountId)
                .containsOnly(signedIn.get(0).accountId());
        var accountId = signedIn.get(0).accountId();
        var card = clientQueries.byPhone(profile.phone()).orElseThrow();
        assertThat(card.accountId()).isEqualTo(accountId);
        assertThat(clientQueries.byAccountId(accountId).orElseThrow().id()).isEqualTo(card.id());
        assertThat(loginMethodsWithSubject(profile.subject())).isEqualTo(1);
        for (var session : signedIn) {
            var authenticated = sessions.authenticate(session.sessionToken()).orElseThrow();
            assertThat(authenticated.actor().clientId()).isEqualTo(card.id());
            assertThat(authenticated.account().loginMethods()).hasSize(1);
        }
    }

    /**
     * Два администратора снимают роль друг с друга одновременно: правило последнего администратора
     * проверяется под блокировкой строки роли {@code DEVELOPER}, поэтому второе снятие видит итог
     * первого и откатывается с {@code LastAccessAdminException}.
     */
    @Test
    void twoDemotionsOfTwoAdminsAtOnceLeaveAtLeastOne() throws Exception {
        var first = signIn(bootstrapDeveloper());
        var second = signIn(profile("Второй"));
        var firstActor = actor(first);
        var developer = role("DEVELOPER");
        access.assignRole(second.accountId(), developer.id(), firstActor);
        var secondActor = actor(second);
        assertThat(firstActor.level(Section.ACCESS)).isEqualTo(AccessLevel.WRITE);
        assertThat(secondActor.level(Section.ACCESS)).isEqualTo(AccessLevel.WRITE);

        var outcomes = race(
                () -> access.revokeRole(second.accountId(), developer.id(), firstActor),
                () -> access.revokeRole(first.accountId(), developer.id(), secondActor));

        assertThat(outcomes).filteredOn(Outcome::succeeded).hasSize(1);
        assertThat(outcomes)
                .filteredOn(outcome -> !outcome.succeeded())
                .singleElement()
                .extracting(Outcome::failure)
                .isInstanceOf(LastAccessAdminException.class);
        var firstDemotedSecond = outcomes.get(0).succeeded();
        var remaining = firstDemotedSecond ? first : second;
        var demoted = firstDemotedSecond ? second : first;
        assertThat(accessLevel(remaining)).isEqualTo(AccessLevel.WRITE);
        assertThat(accessLevel(demoted)).isEqualTo(AccessLevel.NONE);
        assertThat(actor(remaining).level(Section.ACCESS)).isEqualTo(AccessLevel.WRITE);
    }

    /**
     * Вход известного человека одновременно с блокировкой его карточки. Вход либо застал блокировку и
     * получил {@code AccountBlockedException}, либо прошёл раньше — тогда его сессию закрыл слушатель
     * {@code ClientBlocked}, а если сессия открылась уже после проверки карточки и до её блокировки, её
     * удалит первый же {@code authenticate}: заблокированной карточке он сессий не оставляет. В любом
     * случае после обоих потоков живых сессий у аккаунта нет.
     *
     * <p>Вход карточку не блокирует, а только читает, поэтому на практике почти всегда выпадает третья
     * ветка: блокировка успевает зафиксироваться между проверкой карточки и открытием сессии, строка
     * сессии остаётся до первого {@code authenticate}. Первые две ветки детерминированно проверяют
     * {@code IdentityListenersTest.blockingClosesSessions} и {@code LoginFlowTest.blockedCardCannotSignIn}.
     */
    @Test
    void loginRacingWithBlockNeverOpensSessionForBlockedCard() throws Exception {
        var profile = profile("Мария");
        var earlier = signIn(profile);
        var clientId = clientId(earlier);
        var start = loginFlow.start(Provider.FAKE, LoginPurpose.LOGIN, null);
        var code = fake.issueCode(profile);

        var outcomes = race(() -> complete(code, start.state()), () -> clients.block(clientId, "грубит", master));

        var login = outcomes.get(0);
        var blocked = outcomes.get(1);
        assertThat(blocked.failure()).isNull();
        assertThat(clientQueries.get(clientId, master).blocked()).isTrue();
        if (login.succeeded()) {
            var signedIn = (SignedIn) login.result();
            assertThat(signedIn.accountId()).isEqualTo(earlier.accountId());
            assertThat(sessions.authenticate(signedIn.sessionToken())).isEmpty();
        } else {
            assertThat(login.failure()).isInstanceOf(AccountBlockedException.class);
        }
        assertThat(sessions.authenticate(earlier.sessionToken())).isEmpty();
        assertThat(sessionsOf(earlier.accountId())).isZero();
    }

    private LoginResult complete(String code, LoginState state) {
        return loginFlow.complete(Provider.FAKE, new AuthorizationResponse(code, state.state(), null), state, CLIENT);
    }

    private LoginResult complete(FakeProfile profile) {
        var start = loginFlow.start(Provider.FAKE, LoginPurpose.LOGIN, null);
        return complete(fake.issueCode(profile), start.state());
    }

    private RegistrationRequired registrationRequired(FakeProfile profile) {
        var result = complete(profile);
        assertThat(result).isInstanceOf(RegistrationRequired.class);
        return (RegistrationRequired) result;
    }

    private SignedIn register(RegistrationRequired required, FakeProfile profile) {
        return loginFlow.completeRegistration(
                required.ticket(),
                new CompleteRegistration(profile.displayName(), profile.phone(), POLICY_VERSION),
                CLIENT);
    }

    /** Вход известного человека или регистрация нового: администратор из bootstrap переживает тесты. */
    private SignedIn signIn(FakeProfile profile) {
        var result = complete(profile);
        if (result instanceof SignedIn signedIn) {
            return signedIn;
        }
        assertThat(result).isInstanceOf(RegistrationRequired.class);
        return register((RegistrationRequired) result, profile);
    }

    private Actor actor(SignedIn signedIn) {
        return sessions.authenticate(signedIn.sessionToken()).orElseThrow().actor();
    }

    private UUID clientId(SignedIn signedIn) {
        return actor(signedIn).requireClientId();
    }

    private AccessLevel accessLevel(SignedIn signedIn) {
        return access.account(signedIn.accountId(), Actor.developer())
                .effective()
                .get(Section.ACCESS);
    }

    private RoleView role(String code) {
        return access.roles(Actor.developer()).stream()
                .filter(role -> role.code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private int loginMethodsWithSubject(String subject) {
        return jdbc.sql("select count(*) from identity.login_method where provider = 'FAKE' and subject = :subject")
                .param("subject", subject)
                .query(Integer.class)
                .single();
    }

    private int sessionsOf(UUID accountId) {
        return jdbc.sql("select count(*) from identity.session where account_id = :accountId")
                .param("accountId", accountId)
                .query(Integer.class)
                .single();
    }

    private static FakeProfile bootstrapDeveloper() {
        return new FakeProfile(BOOTSTRAP_SUBJECT, "+79990000001", null, "Разработчик", null);
    }

    /** Свой человек на каждый прогон: база одна на все тесты класса, а телефон и {@code subject} уникальны. */
    private static FakeProfile profile(String displayName) {
        var random = ThreadLocalRandom.current();
        var subject = "user-" + UUID.randomUUID();
        var phone = "+7999" + String.format("%07d", random.nextInt(10_000_000));
        return new FakeProfile(subject, phone, null, displayName, null);
    }

    /** Запускает попытки одновременно и возвращает исход каждой. */
    private static List<Outcome> race(Callable<?>... attempts) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(attempts.length)) {
            var futures = new ArrayList<Future<Outcome>>();
            for (var attempt : attempts) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        return new Outcome(attempt.call(), null);
                    } catch (Exception e) {
                        return new Outcome(null, e);
                    }
                }));
            }
            start.countDown();
            var outcomes = new ArrayList<Outcome>();
            for (var future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        }
    }

    private record Outcome(
            @Nullable Object result, @Nullable Exception failure) {

        boolean succeeded() {
            return failure == null;
        }
    }
}
