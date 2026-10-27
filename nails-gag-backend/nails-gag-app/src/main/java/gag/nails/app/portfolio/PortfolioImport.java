package gag.nails.app.portfolio;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.InternalException;
import gag.nails.files.FileStorage;
import gag.nails.files.model.FilePurpose;
import gag.nails.files.model.StoredFileView;
import gag.nails.identity.AccessAdministration;
import gag.nails.identity.model.AccountAccessView;
import gag.nails.portfolio.PortfolioQueries;
import gag.nails.portfolio.PortfolioService;
import gag.nails.portfolio.command.AddWork;
import gag.nails.portfolio.model.WorkSource;
import gag.nails.portfolio.model.WorkView;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Разовый перенос фото в портфолио (спецификация «Файлы и портфолио», §9): только в профиле {@code local} и только
 * если задан {@code nails-gag.portfolio-import.source} — путь к списку в JSON:
 *
 * <pre>[{ "file": "IMG_4241.JPG", "alt": "Молочный квадрат", "caption": null }]</pre>
 *
 * <p>Пути файлов — относительно каталога списка. Каждое фото загружается через {@link FileStorage#store} (вне
 * транзакции, как требует хранилище) и становится работой {@link WorkSource#CLIENT_OUTSIDE_SITE} в конце портфолио —
 * в порядке списка. Повторный запуск ничего не дублирует: фото, чьё содержимое (SHA-256) уже лежит в файле
 * какой-нибудь работы, пропускается; «сирота» с тем же содержимым (загрузка прошлого запуска, не ставшая работой)
 * импортом не считается — её уберёт ночная уборка.
 *
 * <p>Действует от имени разработчика: первого по времени создания незаблокированного аккаунта с ролью
 * {@code DEVELOPER} (её выдаёт {@code nails-gag.identity.bootstrap-developers} при входе), с его итоговыми уровнями
 * прав. Хранилищу нужен аккаунт загрузившего, а привязать файл к работе может только он же. Аккаунт ищется
 * внутренним актором {@link Actor#developer()} без аккаунта. Нет такого аккаунта — запуск прерывается: сначала войти
 * способом из {@code bootstrap-developers}.
 */
@Component
@Profile("local")
public class PortfolioImport implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PortfolioImport.class);

    /** Код встроенной роли «Разработчик» в модуле входа. */
    private static final String DEVELOPER_ROLE = "DEVELOPER";

    private final FileStorage files;
    private final PortfolioService portfolio;
    private final PortfolioQueries works;
    private final AccessAdministration access;
    private final JsonMapper json;
    private final String source;

    PortfolioImport(
            FileStorage files,
            PortfolioService portfolio,
            PortfolioQueries works,
            AccessAdministration access,
            JsonMapper json,
            @Value("${nails-gag.portfolio-import.source:}") String source) {
        this.files = files;
        this.portfolio = portfolio;
        this.works = works;
        this.access = access;
        this.json = json;
        this.source = source;
    }

    /** Фото в списке. */
    record Entry(
            @Nullable String file,
            @Nullable String alt,
            @Nullable String caption) {}

    /** Итог запуска: сколько фото стали работами и сколько пропущено как уже импортированные. */
    public record Result(int imported, int skipped) {}

    @Override
    public void run(ApplicationArguments args) {
        if (source.isBlank()) {
            return;
        }
        importFrom(Path.of(source));
    }

    /**
     * Импортирует фото из списка по порядку. Ошибка на каком-то фото прерывает запуск; импортированные до неё остаются
     * работами, и повторный запуск продолжит с первого непринесённого.
     */
    public Result importFrom(Path list) {
        var directory = list.toAbsolutePath().normalize().getParent();
        var entries = read(list);
        var actor = developer();
        var workFiles = works.all(actor).stream().map(WorkView::fileId).collect(Collectors.toCollection(HashSet::new));
        var imported = 0;
        var skipped = 0;
        for (var entry : entries) {
            var path = directory.resolve(required(entry.file(), "file", list)).normalize();
            var alt = required(entry.alt(), "alt", list);
            if (alreadyImported(sha256(path), workFiles)) {
                log.info("portfolio import: {} is already in the portfolio, skipped", path.getFileName());
                skipped++;
                continue;
            }
            var stored = store(path, actor);
            var work = portfolio.add(
                    new AddWork(stored.id(), alt, entry.caption(), WorkSource.CLIENT_OUTSIDE_SITE, null), actor);
            workFiles.add(work.fileId());
            log.info("portfolio import: {} imported as work {}", path.getFileName(), work.id());
            imported++;
        }
        log.info("portfolio import from {}: {} imported, {} skipped", list, imported, skipped);
        return new Result(imported, skipped);
    }

    private List<Entry> read(Path list) {
        try {
            return json.readValue(list.toFile(), new TypeReference<List<Entry>>() {});
        } catch (JacksonException e) {
            throw new InternalException("cannot read portfolio import list " + list, e);
        }
    }

    private Actor developer() {
        return access.staff(Actor.developer()).stream()
                .filter(account -> !account.blocked())
                .filter(PortfolioImport::isDeveloper)
                .findFirst()
                .map(account -> Actor.of(account.accountId(), account.clientId(), account.effective()))
                .orElseThrow(() -> new InternalException("portfolio import needs a developer account: sign in once"
                        + " with a login method from nails-gag.identity.bootstrap-developers"));
    }

    private static boolean isDeveloper(AccountAccessView account) {
        return account.roles().stream().anyMatch(role -> role.code().equals(DEVELOPER_ROLE));
    }

    /** Фото с этим содержимым уже лежит в файле какой-нибудь работы. */
    private boolean alreadyImported(String sha256, Set<UUID> workFiles) {
        return files.findBySha256(sha256).stream().anyMatch(file -> workFiles.contains(file.id()));
    }

    private StoredFileView store(Path path, Actor actor) {
        try (var content = Files.newInputStream(path)) {
            return files.store(content, Files.size(path), FilePurpose.PORTFOLIO, actor);
        } catch (IOException e) {
            throw new InternalException("cannot read photo " + path, e);
        }
    }

    /** SHA-256 содержимого строчным hex — так же, как его считает хранилище. */
    private static String sha256(Path path) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new InternalException("SHA-256 is not available", e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(path), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (IOException e) {
            throw new InternalException("cannot read photo " + path, e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String required(@Nullable String value, String field, Path list) {
        if (value == null || value.isBlank()) {
            throw new InternalException("portfolio import list " + list + ": every entry needs '" + field + "'");
        }
        return value;
    }
}
