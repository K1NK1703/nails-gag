package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.booking.BookingReferences;
import gag.nails.booking.BookingService;
import gag.nails.booking.command.CreateBooking;
import gag.nails.booking.error.ReferencesLimitReachedException;
import gag.nails.clients.ClientService;
import gag.nails.clients.command.CreateClient;
import gag.nails.common.actor.Actor;
import gag.nails.files.FileStorage;
import gag.nails.files.model.FilePurpose;
import gag.nails.schedule.ScheduleService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Лимит референсов к записи держится при параллельных добавлениях: каждое берёт строку записи под блокировку до
 * подсчёта. Порядок задаётся ожиданием блокировки ({@link ConcurrencySupport#whileRowIsHeld}): второе добавление
 * гарантированно ждёт первое и считает уже с его референсом.
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

    @BeforeEach
    void bookingLocks() {
        locks = new ConcurrencySupport(transaction, jdbc, "%booking.booking%");
    }

    @Test
    void parallelAddsDoNotExceedLimit() throws Exception {
        var card = clients.create(new CreateClient("Мария", null, null, null, null, null), master)
                .id();
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

    private UUID booking(UUID clientId) {
        var day = LocalDate.now(clock).plusDays(DAYS.getAndIncrement());
        var slot = schedule.addSlot(day, LocalTime.NOON, master);
        return bookings.createByMaster(new CreateBooking(clientId, slot.id(), MANICURE, List.of(), null), false, master)
                .id();
    }

    /** Загрузка — вне транзакции, как в приложении: обработка картинки не держит соединение с базой. */
    private UUID upload(Actor uploader) {
        var out = new ByteArrayOutputStream();
        try {
            ImageIO.write(new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB), "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var content = out.toByteArray();
        return files.store(new ByteArrayInputStream(content), content.length, FilePurpose.REFERENCE, uploader)
                .id();
    }
}
