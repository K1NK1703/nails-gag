package gag.nails.portfolio;

import gag.nails.booking.BookingService;
import gag.nails.booking.command.CreateBooking;
import gag.nails.clients.ClientService;
import gag.nails.clients.DocumentVersionService;
import gag.nails.clients.command.CreateClient;
import gag.nails.clients.command.RecordConsent;
import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentSource;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.paging.Paging;
import gag.nails.files.FileStorage;
import gag.nails.files.model.FilePurpose;
import gag.nails.portfolio.command.AddWork;
import gag.nails.portfolio.model.PublicWork;
import gag.nails.portfolio.model.WorkSource;
import gag.nails.portfolio.model.WorkView;
import gag.nails.schedule.ScheduleService;
import gag.nails.testsupport.MutableClock;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Подготовка данных через публичные API модулей. Мастер действует с аккаунтом: файлы портфолио загружает и
 * привязывает один и тот же аккаунт. Часы стоят на четверге 1 октября 2026; записи — на слотах со следующей недели.
 */
abstract class PortfolioTestSupport {

    /** Основная услуга из seed каталога. */
    static final UUID MANICURE = UUID.fromString("019a0c00-0000-7000-8000-000000000001");

    static final UUID MASTER_ACCOUNT = UUID.fromString("0198b2f0-0000-7000-8000-000000000001");
    static final UUID OTHER_ACCOUNT = UUID.fromString("0198b2f0-0000-7000-8000-000000000002");

    /** Мастер со своим аккаунтом. */
    static final Actor MASTER = Actor.of(MASTER_ACCOUNT, null, Actor.masterAccess());

    /** Сотрудник, который только смотрит «Портфолио». */
    static final Actor VIEWER = Actor.of(OTHER_ACCOUNT, null, Map.of(Section.PORTFOLIO, AccessLevel.READ));

    static final Paging ALL = new Paging(0, Paging.MAX_SIZE);

    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 10, 5);

    @Autowired
    PortfolioService portfolio;

    @Autowired
    PortfolioQueries queries;

    @Autowired
    FileStorage files;

    @Autowired
    ClientService clients;

    @Autowired
    DocumentVersionService documents;

    @Autowired
    ScheduleService schedule;

    @Autowired
    BookingService bookings;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

    private int slots;

    /** Файл портфолио, загруженный мастером, 640×480. */
    UUID upload() {
        return upload(FilePurpose.PORTFOLIO, MASTER);
    }

    UUID upload(FilePurpose purpose, Actor uploader) {
        var content = png(640, 480);
        return files.store(new ByteArrayInputStream(content), content.length, purpose, uploader)
                .id();
    }

    WorkView example(String alt) {
        return portfolio.add(new AddWork(upload(), alt, null, WorkSource.EXAMPLE, null), MASTER);
    }

    WorkView outsideSite(String alt) {
        return portfolio.add(new AddWork(upload(), alt, null, WorkSource.CLIENT_OUTSIDE_SITE, null), MASTER);
    }

    /** Работа клиента из его новой записи. */
    WorkView clientWork(UUID clientId, String alt) {
        return portfolio.add(new AddWork(upload(), alt, null, WorkSource.CLIENT, booking(clientId)), MASTER);
    }

    UUID card(String firstName) {
        return clients.create(new CreateClient(firstName, null, null, null, null, null), Actor.master())
                .id();
    }

    /** Запись клиента, которую завёл мастер, — каждый раз на новом слоте. */
    UUID booking(UUID clientId) {
        var slot = schedule.addSlot(FIRST_DAY.plusDays(slots++), LocalTime.NOON, Actor.master());
        return bookings.createByMaster(
                        new CreateBooking(clientId, slot.id(), MANICURE, List.of(), null), false, Actor.master())
                .id();
    }

    void givePhotoConsent(UUID clientId) {
        var kind = ConsentKind.PHOTO_PUBLICATION;
        clients.recordConsent(
                clientId,
                new RecordConsent(kind, documents.current(kind.document()).version(), ConsentSource.ENTERED_BY_MASTER),
                Actor.master());
    }

    void withdrawPhotoConsent(UUID clientId) {
        clients.withdrawConsent(clientId, ConsentKind.PHOTO_PUBLICATION, Actor.master());
    }

    /** Идентификаторы опубликованных работ по порядку. */
    List<UUID> publishedIds() {
        return queries.published(ALL).items().stream().map(PublicWork::id).toList();
    }

    WorkView view(UUID workId) {
        return queries.get(workId, MASTER);
    }

    static byte[] png(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(200, 120, 160));
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        var out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
