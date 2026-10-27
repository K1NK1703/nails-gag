package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import gag.nails.app.portfolio.PortfolioImport;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.InternalException;
import gag.nails.common.paging.Paging;
import gag.nails.files.FileStorage;
import gag.nails.files.model.FilePurpose;
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
import gag.nails.portfolio.PortfolioQueries;
import gag.nails.portfolio.model.PublicWork;
import gag.nails.portfolio.model.WorkSource;
import gag.nails.portfolio.model.WorkView;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Перенос галереи в портфолио ({@link PortfolioImport}) на настоящей базе. Импорт работает только в профиле
 * {@code local}; список при старте не задан, поэтому тесты зовут импорт сами — после того как разработчик из
 * {@code bootstrap-developers} вошёл. База одна на тесты класса: у каждого теста свои картинки (размеры — разные,
 * значит, и содержимое), и работы ищутся по своим описаниям.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.NONE,
        properties = {
            "nails-gag.booking.jobs.enabled=false",
            "nails-gag.identity.jobs.enabled=false",
            "nails-gag.files.jobs.enabled=false",
            "nails-gag.identity.bootstrap-developers=FAKE:" + PortfolioImportTest.DEVELOPER_SUBJECT
        })
@ActiveProfiles("local")
@Import(PostgresTestcontainers.class)
class PortfolioImportTest {

    static final String DEVELOPER_SUBJECT = "portfolio-import-developer";

    private static final String POLICY_VERSION = "2026-09-25";
    private static final ClientInfo CLIENT = new ClientInfo("203.0.113.7", "JUnit");
    private static final FakeProfile DEVELOPER =
            new FakeProfile(DEVELOPER_SUBJECT, "+79990000042", null, "Разработчик", null);

    @Autowired
    PortfolioImport importer;

    @Autowired
    PortfolioQueries works;

    @Autowired
    FileStorage files;

    @Autowired
    LoginFlow loginFlow;

    @Autowired
    FakeProvider fake;

    @Autowired
    Sessions sessions;

    @TempDir
    Path directory;

    @Test
    void portfolioImportIsIdempotent() throws IOException {
        var developer = signInDeveloper();
        Files.write(directory.resolve("first.png"), Pictures.png(64, 48));
        Files.createDirectories(directory.resolve("photos"));
        Files.write(directory.resolve("photos").resolve("second.png"), Pictures.png(48, 64));
        var list = list("""
                [
                  { "file": "first.png", "alt": "Импорт: первая", "caption": "Молочный квадрат" },
                  { "file": "photos/second.png", "alt": "Импорт: вторая", "caption": null }
                ]
                """);

        var first = importer.importFrom(list);
        var second = importer.importFrom(list);

        assertThat(first).isEqualTo(new PortfolioImport.Result(2, 0));
        assertThat(second).isEqualTo(new PortfolioImport.Result(0, 2));
        var imported = imported(developer, Set.of("Импорт: первая", "Импорт: вторая"));
        // В порядке списка, в конце портфолио, видимые: согласие получено вне сайта.
        assertThat(imported)
                .extracting(
                        WorkView::alt,
                        WorkView::caption,
                        WorkView::source,
                        WorkView::published,
                        WorkView::width,
                        WorkView::height)
                .containsExactly(
                        tuple("Импорт: первая", "Молочный квадрат", WorkSource.CLIENT_OUTSIDE_SITE, true, 64, 48),
                        tuple("Импорт: вторая", null, WorkSource.CLIENT_OUTSIDE_SITE, true, 48, 64));
        assertThat(imported.get(0).position()).isLessThan(imported.get(1).position());
        assertThat(works.published(new Paging(0, Paging.MAX_SIZE)).items())
                .extracting(PublicWork::id)
                .containsSubsequence(imported.stream().map(WorkView::id).toList());
    }

    @Test
    void uploadThatDidNotBecomeAWorkIsImportedAgain() throws IOException {
        var developer = signInDeveloper();
        var content = Pictures.png(72, 40);
        Files.write(directory.resolve("third.png"), content);
        // Прошлый запуск загрузил фото, но работой оно не стало: «сирота» с тем же содержимым.
        var orphan = files.store(new ByteArrayInputStream(content), content.length, FilePurpose.PORTFOLIO, developer);
        var list = list("""
                [{ "file": "third.png", "alt": "Импорт: третья", "caption": null }]
                """);

        var result = importer.importFrom(list);

        assertThat(result).isEqualTo(new PortfolioImport.Result(1, 0));
        var imported = imported(developer, Set.of("Импорт: третья"));
        assertThat(imported).hasSize(1);
        assertThat(imported.get(0).fileId()).isNotEqualTo(orphan.id());
    }

    @Test
    void entryWithoutAltStopsTheImportBeforeAnyUpload() throws IOException {
        var developer = signInDeveloper();
        var content = Pictures.png(80, 36);
        Files.write(directory.resolve("fourth.png"), content);
        Files.write(directory.resolve("fifth.png"), Pictures.png(36, 80));
        var list = list("""
                [
                  { "file": "fourth.png", "alt": "Импорт: четвёртая", "caption": null },
                  { "file": "fifth.png", "caption": "Без описания" }
                ]
                """);

        assertThatThrownBy(() -> importer.importFrom(list))
                .isInstanceOf(InternalException.class)
                .hasMessageContaining("entry 2: needs 'alt'");

        assertNothingImported(developer, content, "Импорт: четвёртая");
    }

    @Test
    void missingFileStopsTheImportBeforeAnyUpload() throws IOException {
        var developer = signInDeveloper();
        var content = Pictures.png(88, 32);
        Files.write(directory.resolve("sixth.png"), content);
        var list = list("""
                [
                  { "file": "sixth.png", "alt": "Импорт: шестая", "caption": null },
                  { "file": "missing.png", "alt": "Импорт: нет файла", "caption": null }
                ]
                """);

        assertThatThrownBy(() -> importer.importFrom(list))
                .isInstanceOf(InternalException.class)
                .hasMessageContaining("entry 2: file")
                .hasMessageContaining("missing.png");

        assertNothingImported(developer, content, "Импорт: шестая");
    }

    @Test
    void malformedListIsRejected() throws IOException {
        Files.write(directory.resolve("seventh.png"), Pictures.png(96, 30));

        // Опечатка в имени поля: подпись не должна молча потеряться.
        assertThatThrownBy(() -> importer.importFrom(list("""
                        [{ "file": "seventh.png", "alt": "Импорт: седьмая", "capton": "Опечатка" }]
                        """)))
                .isInstanceOf(InternalException.class)
                .hasMessageContaining("cannot read portfolio import list");
        assertThatThrownBy(() -> importer.importFrom(list("[null]")))
                .isInstanceOf(InternalException.class)
                .hasMessageContaining("entry 1: is null");
        assertThatThrownBy(() -> importer.importFrom(list("null")))
                .isInstanceOf(InternalException.class)
                .hasMessageContaining("must be a JSON array");
        assertThatThrownBy(() -> importer.importFrom(list("""
                        [{ "file": "seventh.png", "alt": "%s", "caption": null }]
                        """.formatted("ж".repeat(201)))))
                .isInstanceOf(InternalException.class)
                .hasMessageContaining("entry 1: 'alt' is longer than 200 characters");
    }

    private void assertNothingImported(Actor actor, byte[] content, String alt) {
        assertThat(files.findBySha256(sha256(content))).isEmpty();
        assertThat(imported(actor, Set.of(alt))).isEmpty();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private Path list(String content) throws IOException {
        var list = directory.resolve("gallery.json");
        Files.writeString(list, content);
        return list;
    }

    private List<WorkView> imported(Actor actor, Set<String> alts) {
        return works.all(actor).stream()
                .filter(work -> alts.contains(work.alt()))
                .toList();
    }

    /** Вход разработчика из bootstrap-developers: при первом входе — регистрация, дальше — обычный вход. */
    private Actor signInDeveloper() {
        var start = loginFlow.start(Provider.FAKE, LoginPurpose.LOGIN, null);
        var response = new AuthorizationResponse(
                fake.issueCode(DEVELOPER), start.state().state(), null);
        var result = loginFlow.complete(Provider.FAKE, response, start.state(), CLIENT);
        var signedIn = result instanceof RegistrationRequired required
                ? loginFlow.completeRegistration(
                        required.ticket(),
                        new CompleteRegistration(DEVELOPER.displayName(), DEVELOPER.phone(), POLICY_VERSION),
                        CLIENT)
                : (SignedIn) result;
        return sessions.authenticate(signedIn.sessionToken()).orElseThrow().actor();
    }
}
