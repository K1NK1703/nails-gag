package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.booking.BookingService;
import gag.nails.booking.command.CreateBooking;
import gag.nails.clients.ClientService;
import gag.nails.clients.command.CreateClient;
import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.ValidationException;
import gag.nails.common.paging.Paging;
import gag.nails.files.FileStorage;
import gag.nails.files.model.FilePurpose;
import gag.nails.portfolio.PortfolioQueries;
import gag.nails.portfolio.PortfolioService;
import gag.nails.portfolio.command.AddWork;
import gag.nails.portfolio.model.PublicWork;
import gag.nails.portfolio.model.WorkSource;
import gag.nails.portfolio.model.WorkView;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
 * Добавление работы клиента и подписчики объединения и обезличивания его карточки встречаются на блокировке клиента
 * в портфолио, добавления и перестановки — на блокировке порядка работ. Порядок задаётся не гонкой, а ожиданием
 * блокировки ({@link ConcurrencySupport#whileRowIsHeld}): второй гарантированно ждёт первого.
 */
@SpringBootTest(webEnvironment = WebEnvironment.NONE, properties = "nails-gag.booking.jobs.enabled=false")
@Import(PostgresTestcontainers.class)
class PortfolioConcurrencyTest {

    private static final UUID MANICURE = UUID.fromString("019a0c00-0000-7000-8000-000000000001");

    /** Слоты далеко в будущем, по дню на запись: база одна на тесты с тем же контекстом. */
    private static final AtomicInteger DAYS = new AtomicInteger(700);

    @Autowired
    PortfolioService portfolio;

    @Autowired
    PortfolioQueries queries;

    @Autowired
    FileStorage files;

    @Autowired
    ClientService clients;

    @Autowired
    ScheduleService schedule;

    @Autowired
    BookingService bookings;

    @Autowired
    Clock clock;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcClient jdbc;

    /** Мастер со своим аккаунтом: файлы портфолио загружает и привязывает один аккаунт. */
    private final Actor master = Actor.of(UUID.randomUUID(), null, Actor.masterAccess());

    private ConcurrencySupport locks;

    @BeforeEach
    void portfolioLocks() {
        locks = new ConcurrencySupport(transaction, jdbc, "%portfolio.%");
    }

    @Test
    void eraseWaitingForAddDeletesTheNewWork() throws Exception {
        var card = card("Мария");
        var add = clientWork(card);

        var erased = locks.whileRowIsHeld(add::run, () -> clients.erase(card));

        assertThat(erased.failure()).isNull();
        assertThat(clientWorks(card)).isEmpty();
        assertThat(files.find(add.fileId)).isEmpty();
    }

    @Test
    void addWaitingForEraseIsRejected() throws Exception {
        var card = card("Мария");
        var add = clientWork(card);

        var added = locks.whileRowIsHeld(() -> clients.erase(card), add::run);

        assertThat(added.failure())
                .isInstanceOfSatisfying(
                        ValidationException.class, e -> assertThat(e.field()).isEqualTo("bookingId"));
        assertThat(clientWorks(card)).isEmpty();
    }

    @Test
    void mergeWaitingForAddMovesTheNewWork() throws Exception {
        var card = card("Мария");
        var survivor = card("Маша");
        var add = clientWork(card);

        var merged = locks.whileRowIsHeld(add::run, () -> clients.merge(card, survivor, Actor.master()));

        assertThat(merged.failure()).isNull();
        assertThat(clientWorks(card)).isEmpty();
        assertThat(clientWorks(survivor)).extracting(WorkView::fileId).containsExactly(add.fileId);
    }

    @Test
    void addWaitingForMergeGetsClientNotFound() throws Exception {
        var card = card("Мария");
        var survivor = card("Маша");
        var add = clientWork(card);

        var added = locks.whileRowIsHeld(() -> clients.merge(card, survivor, Actor.master()), add::run);

        assertThat(added.failure()).isInstanceOf(ClientNotFoundException.class);
        assertThat(clientWorks(card)).isEmpty();
        assertThat(clientWorks(survivor)).isEmpty();
        // Повтор находит запись уже у оставшейся карточки.
        var retried = new ClientWork(upload(), add.bookingId);
        retried.run();
        assertThat(clientWorks(survivor)).extracting(WorkView::fileId).containsExactly(retried.fileId);
    }

    @Test
    void concurrentAddsGetDistinctPositions() throws Exception {
        var first = new AtomicReference<WorkView>();
        var second = new AtomicReference<WorkView>();
        var firstFile = upload();
        var secondFile = upload();

        var added = locks.whileRowIsHeld(
                () -> first.set(portfolio.add(example(firstFile), master)),
                () -> second.set(portfolio.add(example(secondFile), master)));

        assertThat(added.failure()).isNull();
        assertThat(second.get().position()).isGreaterThan(first.get().position());
        // Вне транзакции: страница и total читаются в своей транзакции REPEATABLE READ.
        assertThat(queries.published(new Paging(0, Paging.MAX_SIZE)).items())
                .extracting(PublicWork::id)
                .containsSubsequence(first.get().id(), second.get().id());
    }

    @Test
    void reorderWaitingForAddRejectsTheStaleList() throws Exception {
        portfolio.add(example(upload()), master);
        var seen = queries.all(master).stream().map(WorkView::id).toList().reversed();
        var fileId = upload();

        var reordered = locks.whileRowIsHeld(
                () -> portfolio.add(example(fileId), master), () -> portfolio.reorder(seen, master));

        assertThat(reordered.failure())
                .isInstanceOfSatisfying(
                        ValidationException.class, e -> assertThat(e.field()).isEqualTo("orderedWorkIds"));
        assertThat(queries.all(master).getLast().fileId()).isEqualTo(fileId);

        // Повтор с полным списком фиксируется: позиции переставлены, временные повторы позиций внутри транзакции
        // допускает отложенная уникальность.
        var fresh = queries.all(master).stream().map(WorkView::id).toList().reversed();
        portfolio.reorder(fresh, master);
        assertThat(queries.all(master)).extracting(WorkView::id).containsExactlyElementsOf(fresh);
    }

    /** Добавление работы клиента из его новой записи. */
    private ClientWork clientWork(UUID clientId) {
        return new ClientWork(upload(), booking(clientId));
    }

    /** Добавление работы клиента; файл загружен заранее, вне транзакции добавления. */
    private final class ClientWork {

        final UUID fileId;
        final UUID bookingId;

        ClientWork(UUID fileId, UUID bookingId) {
            this.fileId = fileId;
            this.bookingId = bookingId;
        }

        void run() {
            portfolio.add(new AddWork(fileId, "Френч", null, WorkSource.CLIENT, bookingId), master);
        }
    }

    private List<WorkView> clientWorks(UUID clientId) {
        return queries.all(master).stream()
                .filter(work -> clientId.equals(work.clientId()))
                .toList();
    }

    private static AddWork example(UUID fileId) {
        return new AddWork(fileId, "Пример", null, WorkSource.EXAMPLE, null);
    }

    private UUID card(String firstName) {
        return clients.create(new CreateClient(firstName, null, null, null, null, null), Actor.master())
                .id();
    }

    private UUID booking(UUID clientId) {
        var day = LocalDate.now(clock).plusDays(DAYS.getAndIncrement());
        var slot = schedule.addSlot(day, LocalTime.NOON, Actor.master());
        return bookings.createByMaster(
                        new CreateBooking(clientId, slot.id(), MANICURE, List.of(), null), false, Actor.master())
                .id();
    }

    private UUID upload() {
        var out = new ByteArrayOutputStream();
        try {
            ImageIO.write(new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB), "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var content = out.toByteArray();
        return files.store(new ByteArrayInputStream(content), content.length, FilePurpose.PORTFOLIO, master)
                .id();
    }
}
