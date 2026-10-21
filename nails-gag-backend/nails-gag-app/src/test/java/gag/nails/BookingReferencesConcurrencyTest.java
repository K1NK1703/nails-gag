package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.booking.BookingReferences;
import gag.nails.booking.BookingService;
import gag.nails.booking.command.CreateBooking;
import gag.nails.booking.error.ReferencesLimitReachedException;
import gag.nails.clients.ClientService;
import gag.nails.clients.command.CreateClient;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.ValidationException;
import gag.nails.files.FileStorage;
import gag.nails.files.model.FilePurpose;
import gag.nails.schedule.ScheduleService;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Референсы к записи при параллельных действиях: добавления берут строку записи под блокировку до подсчёта, поэтому
 * лимит держится; добавление и обезличивание клиента встречаются на строке записи, поэтому референс либо удаляется
 * обезличиванием, либо не добавляется. Порядок задаётся ожиданием блокировки ({@link
 * ConcurrencySupport#whileRowIsHeld}): второй гарантированно ждёт первого.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE, properties = "nails-gag.booking.jobs.enabled=false")
@Import(PostgresTestcontainers.class)
class BookingReferencesConcurrencyTest {

    private static final UUID MANICURE = UUID.fromString("019a0c00-0000-7000-8000-000000000001");

    /** Слоты далеко в будущем, по дню на запись: база одна на тесты с тем же контекстом. */
    private static final AtomicInteger DAYS = new AtomicInteger(1200);

    @Autowired
    BookingReferences references;

    @Autowired
    BookingService bookings;

    @Autowired
    ClientService clients;

    @Autowired
    ScheduleService schedule;

    @Autowired
    FileStorage files;

    @Autowired
    Clock clock;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcClient jdbc;

    private final Actor master = Actor.master();

    private ConcurrencySupport locks;

    private Path filesRoot;

    @Value("${nails-gag.files.root}")
    void setFilesRoot(Path root) {
        this.filesRoot = root.toAbsolutePath().normalize();
    }

    @BeforeEach
    void bookingLocks() {
        locks = new ConcurrencySupport(transaction, jdbc, "%booking.booking%");
    }

    @Test
    void parallelAddsDoNotExceedLimit() throws Exception {
        var card = card();
        var booking = booking(card);
        var client = Actor.of(UUID.randomUUID(), card, Map.of());
        for (var i = 0; i < BookingReferences.LIMIT - 1; i++) {
            references.add(booking, upload(client), client);
        }
        var third = upload(client);
        var fourth = upload(client);

        var fourthAdded = locks.whileRowIsHeld(
                () -> references.add(booking, third, client), () -> references.add(booking, fourth, client));

        assertThat(fourthAdded.failure()).isInstanceOf(ReferencesLimitReachedException.class);
        assertThat(references.list(booking, master)).hasSize(BookingReferences.LIMIT);
    }

    @Test
    void eraseWaitingForAddRemovesTheNewReference() throws Exception {
        var card = card();
        var booking = booking(card);
        var staff = staff();
        var file = upload(staff);

        var erased = locks.whileRowIsHeld(() -> references.add(booking, file, staff), () -> clients.erase(card));

        assertThat(erased.failure()).isNull();
        assertThat(references.list(booking, master)).isEmpty();
        assertThat(files.find(file)).isEmpty();
        assertThat(filesRoot.resolve("reference").resolve(file.toString())).doesNotExist();
    }

    @Test
    void addWaitingForEraseIsRejected() throws Exception {
        var card = card();
        var booking = booking(card);
        var staff = staff();
        var file = upload(staff);

        var added = locks.whileRowIsHeld(() -> clients.erase(card), () -> references.add(booking, file, staff));

        assertThat(added.failure())
                .isInstanceOfSatisfying(
                        ValidationException.class, e -> assertThat(e.field()).isEqualTo("bookingId"));
        assertThat(references.list(booking, master)).isEmpty();
        // Файл остался «сиротой»: его заберёт уборка хранилища.
        assertThat(jdbc.sql("select attached_at is null from files.stored_file where id = :id")
                        .param("id", file)
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    private UUID card() {
        return clients.create(new CreateClient("Мария", null, null, null, null, null), master)
                .id();
    }

    /** Мастер со своим аккаунтом: загружает и прикладывает файлы. */
    private static Actor staff() {
        return Actor.of(UUID.randomUUID(), null, Actor.masterAccess());
    }

    private UUID booking(UUID clientId) {
        var day = LocalDate.now(clock).plusDays(DAYS.getAndIncrement());
        var slot = schedule.addSlot(day, LocalTime.NOON, master);
        return bookings.createByMaster(new CreateBooking(clientId, slot.id(), MANICURE, List.of(), null), false, master)
                .id();
    }

    /** Загрузка — вне транзакции, как в приложении: обработка картинки не держит соединение с базой. */
    private UUID upload(Actor uploader) {
        var content = Pictures.png(64, 48);
        return files.store(new ByteArrayInputStream(content), content.length, FilePurpose.REFERENCE, uploader)
                .id();
    }
}
