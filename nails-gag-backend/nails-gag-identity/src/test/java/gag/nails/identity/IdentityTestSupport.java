package gag.nails.identity;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.clients.ClientQueries;
import gag.nails.clients.ClientService;
import gag.nails.common.actor.Actor;
import gag.nails.identity.testing.FakeProfile;
import gag.nails.identity.testing.FakeProvider;
import gag.nails.testsupport.MutableClock;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Вход через публичный API с подставным провайдером. Один тестовый {@code subject} объявлен в
 * {@code bootstrap-developers}: так появляется первый администратор, от имени которого выдаются права.
 *
 * <p>Тест идёт в одной транзакции с откатом ({@code @ModuleTest}). Поэтому отклонённая операция,
 * которая успела что-то записать до своего исключения (правило последнего администратора проверяется
 * после изменения), оставляет эти записи видимыми до конца теста — в бою их откатила бы транзакция.
 * Тесты, которым важен откат, либо повторяют операцию обратного знака, либо наследуют
 * {@link CommittingIdentityTest} и идут на настоящих транзакциях.
 */
abstract class IdentityTestSupport {

    /** Действующая версия политики из seed модуля clients. */
    static final String POLICY_VERSION = "2026-09-25";

    /** {@code subject} из {@code bootstrap-developers}: его аккаунт получает роль «Разработчик». */
    static final String BOOTSTRAP_SUBJECT = "bootstrap-developer";

    static final ClientInfo CLIENT = new ClientInfo("203.0.113.7", "JUnit");

    @DynamicPropertySource
    static void bootstrapDeveloper(DynamicPropertyRegistry registry) {
        registry.add("nails-gag.identity.bootstrap-developers", () -> "FAKE:" + BOOTSTRAP_SUBJECT);
    }

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
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    final Actor master = Actor.master();

    static FakeProfile profile(String subject, String phone) {
        return new FakeProfile(subject, phone, null, "Клиент " + subject, null);
    }

    static FakeProfile profile(String subject, String phone, String email) {
        return new FakeProfile(subject, phone, email, "Клиент " + subject, null);
    }

    /** {@code start} → {@code issueCode} → {@code complete} с целью входа. */
    LoginResult complete(FakeProfile profile) {
        var start = loginFlow.start(Provider.FAKE, LoginPurpose.LOGIN, null);
        return complete(profile, start.state());
    }

    LoginResult complete(FakeProfile profile, LoginState state) {
        var code = fake.issueCode(profile);
        return loginFlow.complete(Provider.FAKE, new AuthorizationResponse(code, state.state(), null), state, CLIENT);
    }

    /** Первый вход: регистрация с телефоном профиля (или введённым вручную) и согласием на политику. */
    SignedIn signInFirstTime(FakeProfile profile) {
        return signInFirstTime(profile, profile.phone() == null ? "+79990000000" : profile.phone());
    }

    SignedIn signInFirstTime(FakeProfile profile, String enteredPhone) {
        var required = registrationRequired(profile);
        var name = profile.displayName() == null ? "Без имени" : profile.displayName();
        return loginFlow.completeRegistration(
                required.ticket(), new CompleteRegistration(name, enteredPhone, POLICY_VERSION), CLIENT);
    }

    RegistrationRequired registrationRequired(FakeProfile profile) {
        var result = complete(profile);
        assertThat(result).isInstanceOf(RegistrationRequired.class);
        return (RegistrationRequired) result;
    }

    /** Повторный вход известного человека. */
    SignedIn signIn(FakeProfile profile) {
        var result = complete(profile);
        assertThat(result).isInstanceOf(SignedIn.class);
        return (SignedIn) result;
    }

    /** Первый администратор: регистрация способа входа из {@code bootstrap-developers}. */
    SignedIn developer() {
        return signInFirstTime(new FakeProfile(BOOTSTRAP_SUBJECT, "+79990000001", null, "Разработчик", null));
    }

    Actor actor(SignedIn signedIn) {
        return actor(signedIn.sessionToken());
    }

    Actor actor(String token) {
        return sessions.authenticate(token).orElseThrow().actor();
    }

    UUID clientId(SignedIn signedIn) {
        return actor(signedIn).requireClientId();
    }

    AccountView me(SignedIn signedIn) {
        return accounts.me(actor(signedIn));
    }

    RoleView role(String code) {
        return access.roles(Actor.developer()).stream()
                .filter(role -> role.code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    List<LoginEventView> loginJournal() {
        return access.loginJournal(500, Actor.developer());
    }

    List<AccessChangeView> accessJournal() {
        return access.accessJournal(500, Actor.developer());
    }
}
