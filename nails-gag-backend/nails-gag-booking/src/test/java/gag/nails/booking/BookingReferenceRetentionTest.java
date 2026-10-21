package gag.nails.booking;

import static gag.nails.booking.ReferenceUploads.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.booking.model.BookingView;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.InternalException;
import gag.nails.files.FileStorage;
import gag.nails.schedule.model.SlotView;
import gag.nails.testsupport.ModuleTest;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Срок хранения референсов ({@link BookingMaintenance#removeExpiredReferences}): 30 дней после визита или отмены. На
 * настоящих транзакциях — удаление идёт пачками в своих транзакциях, а файлы на диске стираются после фиксации.
 */
@ModuleTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BookingReferenceRetentionTest extends BookingTestSupport {

    @Autowired
    BookingMaintenance maintenance;

    @Autowired
    BookingReferences references;

    @Autowired
    EarlierTimeOffers offers;

    @Autowired
    VisitMarks marks;

    @Autowired
    FileStorage files;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transaction;

    /** Свои зафиксированные данные теста: после него удаляются только они. */
    CommittedData committed;

    @Value("${nails-gag.files.root}")
    void setFilesRoot(Path root) {
        committed = new CommittedData(jdbc, root);
    }

    @AfterEach
    void removeCommittedData() throws IOException {
        committed.removeAll();
    }

    /** Карточка регистрируется: её записи, референсы и согласия удалятся после теста. */
    @Override
    UUID client(String firstName) {
        return committed.client(super.client(firstName));
    }

    @Override
    SlotView slot(LocalDate day, String time) {
        var slot = super.slot(day, time);
        committed.slot(slot.id());
        return slot;
    }

    @Test
    void referencesRemovedThirtyDaysAfterVisit() {
        var anna = client("Анна");
        var monday = slot(MONDAY, "12:00");
        var completed = book(anna, monday);
        var completedFile = reference(completed, anna);
        var tuesday = slot(TUESDAY, "12:00");
        var noShow = bookings.createByMaster(manicure(anna, tuesday), false, master);
        var noShowFile = reference(noShow, anna);
        timeIsAfterStartOf(tuesday, Duration.ofHours(1));
        marks.complete(completed.id(), master);
        marks.markNoShow(noShow.id(), master);

        // Отметки поставлены позже визита, а срок считается от начала визита.
        clock.set(monday.startsAt().plus(Duration.ofDays(30)));
        assertThat(maintenance.removeExpiredReferences()).isZero();
        assertThat(references.list(completed.id(), master)).hasSize(1);

        clock.set(monday.startsAt().plus(Duration.ofDays(30)).plusSeconds(1));
        assertThat(maintenance.removeExpiredReferences()).isEqualTo(1);
        assertRemoved(completed, completedFile);
        assertThat(references.list(noShow.id(), master)).hasSize(1);

        clock.set(tuesday.startsAt().plus(Duration.ofDays(30)).plusSeconds(1));
        assertThat(maintenance.removeExpiredReferences()).isEqualTo(1);
        assertRemoved(noShow, noShowFile);
        assertThat(maintenance.removeExpiredReferences()).isZero();
    }

    @Test
    void referencesExpireThirtyDaysAfterAgreedEarlierStart() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        var file = reference(booking, anna);
        var agreedStart = slot.startsAt().minus(Duration.ofMinutes(90));
        offers.propose(booking.id(), agreedStart, master);
        offers.respond(booking.id(), true, Actor.client(anna));
        timeIsAfterStartOf(slot, Duration.ofHours(1));
        marks.complete(booking.id(), master);

        clock.set(agreedStart.plus(Duration.ofDays(30)).plusSeconds(1));

        assertThat(maintenance.removeExpiredReferences()).isEqualTo(1);
        assertRemoved(booking, file);
    }

    @Test
    void referencesOfUnmarkedVisitRemovedThirtyDaysAfterIt() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        var file = reference(booking, anna);

        // Мастер так и не отметил визит, но политика обещает 30 дней после визита.
        clock.set(slot.startsAt().plus(Duration.ofDays(30)));
        assertThat(maintenance.removeExpiredReferences()).isZero();

        clock.set(slot.startsAt().plus(Duration.ofDays(30)).plusSeconds(1));
        assertThat(maintenance.removeExpiredReferences()).isEqualTo(1);
        assertRemoved(booking, file);
    }

    @Test
    void referencesOfActiveBookingKept() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        var file = reference(booking, anna);

        // Визит ещё не прошёл.
        assertThat(maintenance.removeExpiredReferences()).isZero();

        // Визит прошёл меньше 30 дней назад и не отмечен.
        timeIsAfterStartOf(slot, Duration.ofDays(29));
        assertThat(maintenance.removeExpiredReferences()).isZero();

        assertThat(references.list(booking.id(), master)).hasSize(1);
        assertThat(files.find(file)).isPresent();
        assertThat(committed.referenceDirectory(file)).exists();
    }

    @Test
    void referencesRemovedThirtyDaysAfterCancellation() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        var file = reference(booking, anna);
        clock.advance(Duration.ofHours(1));
        var cancelledAt = bookings.cancel(booking.id(), Actor.client(anna)).cancelledAt();

        clock.set(cancelledAt.plus(Duration.ofDays(30)));
        assertThat(maintenance.removeExpiredReferences()).isZero();

        clock.set(cancelledAt.plus(Duration.ofDays(30)).plusSeconds(1));
        assertThat(maintenance.removeExpiredReferences()).isEqualTo(1);
        assertRemoved(booking, file);
    }

    /** Пачка — своя транзакция: внутри чужой фиксация пачек и стирание файлов не гарантированы. */
    @Test
    void removalRefusesToRunInsideTransaction() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> maintenance.removeExpiredReferences()))
                .isInstanceOf(InternalException.class);
    }

    /** Клиент прикладывает к записи референс, загруженный своим аккаунтом. */
    private UUID reference(BookingView booking, UUID clientId) {
        var actor = signedIn(clientId);
        return references
                .add(booking.id(), committed.file(ReferenceUploads.upload(files, actor)), actor)
                .fileId();
    }

    private void assertRemoved(BookingView booking, UUID file) {
        assertThat(references.list(booking.id(), master)).isEmpty();
        assertThat(files.find(file)).isEmpty();
        assertThat(committed.referenceDirectory(file)).doesNotExist();
    }
}
