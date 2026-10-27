package gag.nails.app.portfolio;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.InternalException;
import gag.nails.files.FileStorage;
import gag.nails.files.model.FilePurpose;
import gag.nails.files.model.StoredFileView;
import gag.nails.identity.AccessAdministration;
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
import java.util.ArrayList;
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
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Разовый перенос фото в портфолио (спецификация «Файлы и портфолио», §9): только в профиле {@code local} и только
 * если задан {@code nails-gag.portfolio-import.source} — путь к списку в JSON:
 *
 * <pre>[{ "file": "IMG_4241.JPG", "alt": "Молочный квадрат", "caption": null }]</pre>
 *
 * <p>Пути файлов — относительно каталога списка. Весь список проверяется до первой загрузки: у каждой записи есть
 * {@code file} и {@code alt}, файл существует, описание и подпись не длиннее 200 символов, лишних полей нет. Затем
 * каждое фото загружается через {@link FileStorage#store} (вне транзакции, как требует хранилище) и становится работой
 * {@link WorkSource#CLIENT_OUTSIDE_SITE} в конце портфолио — в порядке списка. Повторный запуск ничего не дублирует:
 * фото, чьё содержимое (SHA-256) уже лежит в файле какой-нибудь работы, пропускается; «сирота» с тем же содержимым
 * (загрузка прошлого запуска, не ставшая работой) импортом не считается — её уберёт ночная уборка. Работу, которую
 * мастер удалил, повторный импорт вернёт.
 *
 * <p>Действует от имени первого по времени создания незаблокированного аккаунта с правом записи в «Портфолио» (на
 * свежей базе это разработчик из {@code nails-gag.identity.bootstrap-developers} после первого входа), с его
 * итоговыми уровнями прав: хранилищу нужен аккаунт загрузившего, а привязать файл к работе может только он же.
 * Аккаунт ищется внутренним актором {@link Actor#developer()} без аккаунта. Нет такого аккаунта — запуск прерывается.
 */
@Component
@Profile("local")
public class PortfolioImport implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PortfolioImport.class);

    /** Предел длины описания и подписи работы, в кодовых точках — как проверяет портфолио. */
    private static final int MAX_TEXT_LENGTH = 200;

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

    /** Фото в списке, как оно записано в JSON. */
    record Entry(
            @Nullable String file,
            @Nullable String alt,
            @Nullable String caption) {}

    /** Проверенная запись: номер в списке (с единицы), файл как в списке и его путь. */
    private record Photo(
            int number,
            String file,
            Path path,
            String alt,
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
     * Импортирует фото из списка по порядку. Ошибка в списке прерывает запуск до первой загрузки; ошибка при загрузке
     * фото — на нём: импортированные до него остаются работами, и повторный запуск продолжит с первого непринесённого.
     */
    public Result importFrom(Path list) {
        var photos = check(list, read(list));
        var actor = portfolioWriter();
        log.info("portfolio import from {}: {} entries as account {}", list, photos.size(), actor.accountId());
        var workFiles = works.all(actor).stream().map(WorkView::fileId).collect(Collectors.toCollection(HashSet::new));
        var imported = 0;
        var skipped = 0;
        for (var photo : photos) {
            if (alreadyImported(sha256(photo.path()), workFiles)) {
                log.info("portfolio import: {} is already in the portfolio, skipped", photo.file());
                skipped++;
                continue;
            }
            var stored = store(photo.path(), actor);
            var work = portfolio.add(
                    new AddWork(stored.id(), photo.alt(), photo.caption(), WorkSource.CLIENT_OUTSIDE_SITE, null),
                    actor);
            workFiles.add(work.fileId());
            log.info("portfolio import: {} imported as work {}", photo.file(), work.id());
            imported++;
        }
        log.info("portfolio import from {}: {} imported, {} skipped", list, imported, skipped);
        return new Result(imported, skipped);
    }

    /** Список целиком; лишнее поле в записи — ошибка, а не молча потерянная подпись. */
    private List<@Nullable Entry> read(Path list) {
        List<@Nullable Entry> entries;
        try {
            entries = json.readerFor(new TypeReference<List<@Nullable Entry>>() {})
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(list.toFile());
        } catch (JacksonException e) {
            throw new InternalException("cannot read portfolio import list " + list, e);
        }
        if (entries == null) {
            throw new InternalException("portfolio import list " + list + " must be a JSON array, got null");
        }
        return entries;
    }

    /** Проверяет все записи до первой загрузки: ошибка в середине списка не должна оставить половину работ. */
    private static List<Photo> check(Path list, List<@Nullable Entry> entries) {
        var directory = list.toAbsolutePath().normalize().getParent();
        var photos = new ArrayList<Photo>(entries.size());
        for (var i = 0; i < entries.size(); i++) {
            var number = i + 1;
            var entry = entries.get(i);
            if (entry == null) {
                throw invalid(list, number, "is null");
            }
            var file = required(entry.file(), "file", list, number);
            var alt = required(entry.alt(), "alt", list, number);
            requireLength(alt, "alt", list, number);
            var caption = entry.caption();
            if (caption != null) {
                requireLength(caption, "caption", list, number);
            }
            var path = directory.resolve(file).normalize();
            if (!Files.isRegularFile(path)) {
                throw invalid(list, number, "file " + path + " does not exist or is not a regular file");
            }
            photos.add(new Photo(number, file, path, alt, caption));
        }
        return photos;
    }

    private Actor portfolioWriter() {
        return access.staff(Actor.developer()).stream()
                .filter(account -> !account.blocked())
                .filter(account -> account.effective()
                        .getOrDefault(Section.PORTFOLIO, AccessLevel.NONE)
                        .allows(AccessLevel.WRITE))
                .findFirst()
                .map(account -> Actor.of(account.accountId(), account.clientId(), account.effective()))
                .orElseThrow(() -> new InternalException("portfolio import needs an account with write access to"
                        + " Portfolio: sign in once with a login method from nails-gag.identity.bootstrap-developers"));
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

    private static String required(@Nullable String value, String field, Path list, int number) {
        if (value == null || value.isBlank()) {
            throw invalid(list, number, "needs '" + field + "'");
        }
        return value;
    }

    private static void requireLength(String value, String field, Path list, int number) {
        if (value.codePointCount(0, value.length()) > MAX_TEXT_LENGTH) {
            throw invalid(list, number, "'" + field + "' is longer than " + MAX_TEXT_LENGTH + " characters");
        }
    }

    private static InternalException invalid(Path list, int number, String problem) {
        return new InternalException("portfolio import list " + list + ", entry " + number + ": " + problem);
    }
}
