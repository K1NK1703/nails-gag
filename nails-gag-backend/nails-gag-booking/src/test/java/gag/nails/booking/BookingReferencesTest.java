package gag.nails.booking;

import static gag.nails.booking.ReferenceUploads.HEIGHT;
import static gag.nails.booking.ReferenceUploads.WIDTH;
import static gag.nails.booking.ReferenceUploads.signedIn;
import static gag.nails.booking.ReferenceUploads.signedInMaster;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.booking.error.ReferencesClosedException;
import gag.nails.booking.error.ReferencesLimitReachedException;
import gag.nails.booking.model.BookingView;
import gag.nails.booking.model.ReferenceAuthor;
import gag.nails.booking.model.ReferenceView;
import gag.nails.clients.command.RegisterClient;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.files.FileStorage;
import gag.nails.files.model.FilePurpose;
import gag.nails.schedule.model.SlotView;
import gag.nails.testsupport.ModuleTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ModuleTest
class BookingReferencesTest extends BookingTestSupport {

    @Autowired
    BookingReferences references;

    @Autowired
    FileStorage files;

    @Autowired
    VisitMarks marks;

    @Autowired
    EarlierTimeOffers offers;

    @PersistenceContext
    EntityManager entityManager;

    @Test
    void clientAddsUpToThreeReferences() {
        var anna = client("Анна");
        var actor = signedIn(anna);
        var booking = book(anna, slot(MONDAY, "12:00"));

        var added = new ArrayList<ReferenceView>();
        for (var i = 0; i < 3; i++) {
            clock.advance(Duration.ofMinutes(1));
            added.add(references.add(booking.id(), upload(actor), actor));
        }

        assertThat(added).allSatisfy(reference -> {
            assertThat(reference.addedBy()).isEqualTo(ReferenceAuthor.CLIENT);
            assertThat(reference.width()).isEqualTo(WIDTH);
            assertThat(reference.height()).isEqualTo(HEIGHT);
        });
        assertThat(added).extracting(ReferenceView::createdAt).isSorted().doesNotHaveDuplicates();
        assertThat(references.list(booking.id(), actor)).containsExactlyElementsOf(added);
        assertThat(references.list(booking.id(), master)).containsExactlyElementsOf(added);
    }

    @Test
    void fourthReferenceConflicts() {
        var anna = client("Анна");
        var actor = signedIn(anna);
        var booking = book(anna, slot(MONDAY, "12:00"));
        for (var i = 0; i < 3; i++) {
            references.add(booking.id(), upload(actor), actor);
        }
        var fourth = upload(actor);

        assertThatThrownBy(() -> references.add(booking.id(), fourth, actor))
                .isInstanceOfSatisfying(ReferencesLimitReachedException.class, e -> {
                    assertThat(e.code()).isEqualTo("booking.references_limit_reached");
                    assertThat(e.params()).containsEntry("limit", 3);
                });
        assertThat(references.list(booking.id(), master)).hasSize(3);
        // Отказ лимита — до привязки: файл остаётся «сиротой» и уйдёт с уборкой хранилища.
        assertThat(files.find(fourth)).isPresent();
    }

    @Test
    void staffCannotExceedTheLimitEither() {
        var anna = client("Анна");
        var staff = signedInMaster();
        var booking = book(anna, slot(MONDAY, "12:00"));
        for (var i = 0; i < 3; i++) {
            references.add(booking.id(), upload(staff), staff);
        }
        var fourth = upload(staff);

        assertThatThrownBy(() -> references.add(booking.id(), fourth, staff))
                .isInstanceOf(ReferencesLimitReachedException.class);
    }

    @Test
    void clientCannotAddAfterVisitStarts() {
        var anna = client("Анна");
        var actor = signedIn(anna);
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        var kept = references.add(booking.id(), upload(actor), actor);
        var late = upload(actor);

        timeIsAfterStartOf(slot, Duration.ZERO);

        assertThatThrownBy(() -> references.add(booking.id(), late, actor))
                .isInstanceOfSatisfying(
                        ReferencesClosedException.class,
                        e -> assertThat(e.code()).isEqualTo("booking.references_closed"));
        assertThatThrownBy(() -> references.remove(booking.id(), kept.fileId(), actor))
                .isInstanceOf(ReferencesClosedException.class);
        assertThat(references.list(booking.id(), actor)).containsExactly(kept);
    }

    @Test
    void clientReferencesCloseAtAgreedEarlierStart() {
        var anna = client("Анна");
        var actor = signedIn(anna);
        var slot = slot(MONDAY, "12:00");
        var booking = book(anna, slot);
        var kept = references.add(booking.id(), upload(actor), actor);
        var agreedStart = slot.startsAt().minus(Duration.ofMinutes(90));
        offers.propose(booking.id(), agreedStart, master);
        offers.respond(booking.id(), true, Actor.client(anna));
        var late = upload(actor);

        // Слот ещё не начался, но клиент согласился прийти раньше — визит уже идёт.
        clock.set(agreedStart.plus(Duration.ofMinutes(1)));

        assertThatThrownBy(() -> references.add(booking.id(), late, actor))
                .isInstanceOf(ReferencesClosedException.class);
        assertThatThrownBy(() -> references.remove(booking.id(), kept.fileId(), actor))
                .isInstanceOf(ReferencesClosedException.class);
        assertThat(references.list(booking.id(), actor)).containsExactly(kept);
    }

    @Test
    void clientRemovesReferenceAddedByStaff() {
        var anna = client("Анна");
        var actor = signedIn(anna);
        var staff = signedInMaster();
        var booking = book(anna, slot(MONDAY, "12:00"));
        var byStaff = references.add(booking.id(), upload(staff), staff);

        references.remove(booking.id(), byStaff.fileId(), actor);

        assertThat(references.list(booking.id(), actor)).isEmpty();
        assertThat(files.find(byStaff.fileId())).isEmpty();
    }

    @Test
    void clientCannotAddToCancelledBookingButMayRemoveBeforeItsSlot() {
        var anna = client("Анна");
        var actor = signedIn(anna);
        var booking = book(anna, slot(MONDAY, "12:00"));
        var kept = references.add(booking.id(), upload(actor), actor);
        bookings.cancel(booking.id(), Actor.client(anna));
        var late = upload(actor);

        assertThatThrownBy(() -> references.add(booking.id(), late, actor))
                .isInstanceOf(ReferencesClosedException.class);

        references.remove(booking.id(), kept.fileId(), actor);
        assertThat(references.list(booking.id(), actor)).isEmpty();
    }

    @Test
    void staffAddsAnytime() {
        var anna = client("Анна");
        var staff = signedInMaster();
        var slot = slot(MONDAY, "12:00");
        var completed = book(anna, slot);
        var cancelled = byMaster(anna, slot(TUESDAY, "12:00"));
        bookings.cancel(cancelled.id(), master);
        timeIsAfterStartOf(slot, Duration.ofHours(2));
        marks.complete(completed.id(), master);

        var afterVisit = references.add(completed.id(), upload(staff), staff);
        var toCancelled = references.add(cancelled.id(), upload(staff), staff);

        assertThat(afterVisit.addedBy()).isEqualTo(ReferenceAuthor.STAFF);
        assertThat(references.list(completed.id(), signedIn(anna))).containsExactly(afterVisit);
        assertThat(references.list(cancelled.id(), master)).containsExactly(toCancelled);

        references.remove(completed.id(), afterVisit.fileId(), staff);
        assertThat(references.list(completed.id(), master)).isEmpty();
    }

    @Test
    void readerSeesReferencesButCannotChangeThem() {
        var anna = client("Анна");
        var actor = signedIn(anna);
        var booking = book(anna, slot(MONDAY, "12:00"));
        var reference = references.add(booking.id(), upload(actor), actor);
        var reader = Actor.of(UUID.randomUUID(), null, Map.of(Section.BOOKINGS, AccessLevel.READ));
        var file = upload(reader);

        assertThat(references.list(booking.id(), reader)).containsExactly(reference);
        assertThat(references.imageVisible(booking.id(), reference.fileId(), reader))
                .isTrue();
        assertThatThrownBy(() -> references.add(booking.id(), file, reader)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> references.remove(booking.id(), reference.fileId(), reader))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void foreignClientCannotSeeReferences() {
        var anna = client("Анна");
        var actor = signedIn(anna);
        var booking = book(anna, slot(MONDAY, "12:00"));
        var reference = references.add(booking.id(), upload(actor), actor);
        var maria = signedIn(client("Мария"));
        var file = upload(maria);

        // Та же реакция, что у чтения самой записи.
        assertThatThrownBy(() -> queries.get(booking.id(), maria)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> references.list(booking.id(), maria)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> references.imageVisible(booking.id(), reference.fileId(), maria))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> references.add(booking.id(), file, maria)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> references.remove(booking.id(), reference.fileId(), maria))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(references.list(booking.id(), actor)).containsExactly(reference);
    }

    @Test
    void imageIsVisibleOnlyAsReferenceOfItsBooking() {
        var anna = client("Анна");
        var actor = signedIn(anna);
        var booking = book(anna, slot(MONDAY, "12:00"));
        var other = byMaster(anna, slot(TUESDAY, "12:00"));
        var reference = references.add(booking.id(), upload(actor), actor);
        var orphan = upload(actor);

        assertThat(references.imageVisible(booking.id(), reference.fileId(), actor))
                .isTrue();
        assertThat(references.imageVisible(booking.id(), reference.fileId(), master))
                .isTrue();
        assertThat(references.imageVisible(other.id(), reference.fileId(), actor))
                .isFalse();
        assertThat(references.imageVisible(booking.id(), orphan, actor)).isFalse();
    }

    @Test
    void removeDeletesFile() {
        var anna = client("Анна");
        var actor = signedIn(anna);
        var booking = book(anna, slot(MONDAY, "12:00"));
        var reference = references.add(booking.id(), upload(actor), actor);

        references.remove(booking.id(), reference.fileId(), actor);

        assertThat(references.list(booking.id(), actor)).isEmpty();
        assertThat(files.find(reference.fileId())).isEmpty();
        // Повтор ничего не делает.
        references.remove(booking.id(), reference.fileId(), actor);
    }

    @Test
    void removeIgnoresFileOfAnotherBooking() {
        var anna = client("Анна");
        var actor = signedIn(anna);
        var booking = book(anna, slot(MONDAY, "12:00"));
        var other = byMaster(anna, slot(TUESDAY, "12:00"));
        var reference = references.add(booking.id(), upload(actor), actor);

        references.remove(other.id(), reference.fileId(), actor);

        assertThat(references.list(booking.id(), actor)).containsExactly(reference);
        assertThat(files.find(reference.fileId())).isPresent();
    }

    @Test
    void rescheduleKeepsReferences() {
        var anna = client("Анна");
        var actor = signedIn(anna);
        var booking = book(anna, slot(MONDAY, "12:00"));
        var reference = references.add(booking.id(), upload(actor), actor);

        var moved = bookings.reschedule(booking.id(), slot(TUESDAY, "15:00").id(), Actor.client(anna));

        assertThat(moved.slotStartsAt()).isNotEqualTo(booking.slotStartsAt());
        assertThat(references.list(booking.id(), actor)).containsExactly(reference);
    }

    @Test
    void fileOfAnotherUploaderIsRejected() {
        var anna = client("Анна");
        var actor = signedIn(anna);
        var booking = book(anna, slot(MONDAY, "12:00"));
        var foreign = upload(signedInMaster());

        assertThatThrownBy(() -> references.add(booking.id(), foreign, actor))
                .isInstanceOfSatisfying(
                        ValidationException.class, e -> assertThat(e.field()).isEqualTo("fileId"));
    }

    @Test
    void clientWithoutAccountHasNoFilesToAdd() {
        var anna = client("Анна");
        var booking = book(anna, slot(MONDAY, "12:00"));
        var file = upload(signedIn(anna));

        assertThatThrownBy(() -> references.add(booking.id(), file, Actor.client(anna)))
                .isInstanceOfSatisfying(
                        ValidationException.class, e -> assertThat(e.field()).isEqualTo("fileId"));
    }

    @Test
    void portfolioFileIsRejected() {
        var anna = client("Анна");
        var actor = signedIn(anna);
        var booking = book(anna, slot(MONDAY, "12:00"));
        var portfolioFile = ReferenceUploads.upload(files, actor, FilePurpose.PORTFOLIO);

        assertThatThrownBy(() -> references.add(booking.id(), portfolioFile, actor))
                .isInstanceOfSatisfying(
                        ValidationException.class, e -> assertThat(e.field()).isEqualTo("fileId"));
    }

    @Test
    void fileOfAnotherBookingIsRejected() {
        var anna = client("Анна");
        var actor = signedIn(anna);
        var booking = book(anna, slot(MONDAY, "12:00"));
        var other = byMaster(anna, slot(TUESDAY, "12:00"));
        var reference = references.add(booking.id(), upload(actor), actor);

        assertThatThrownBy(() -> references.add(other.id(), reference.fileId(), actor))
                .isInstanceOfSatisfying(
                        ValidationException.class, e -> assertThat(e.field()).isEqualTo("fileId"));
    }

    @Test
    void eraseRemovesReferencesOfAllBookingsWithTheirFiles() {
        var anna = client("Анна");
        var actor = signedIn(anna);
        var started = slot(MONDAY, "12:00");
        var startedBooking = book(anna, started);
        var future = byMaster(anna, slot(TUESDAY, "12:00"));
        var first = references.add(startedBooking.id(), upload(actor), actor);
        var second = references.add(future.id(), upload(actor), actor);
        timeIsAfterStartOf(started, Duration.ofMinutes(30));

        clients.erase(anna);
        entityManager.flush();
        entityManager.clear();

        assertThat(references.list(startedBooking.id(), master)).isEmpty();
        assertThat(references.list(future.id(), master)).isEmpty();
        assertThat(files.find(first.fileId())).isEmpty();
        assertThat(files.find(second.fileId())).isEmpty();
    }

    @Test
    void nobodyAddsReferencesToBookingsOfErasedClient() {
        var anna = client("Анна");
        var staff = signedInMaster();
        var started = slot(MONDAY, "12:00");
        var startedBooking = book(anna, started);
        var future = byMaster(anna, slot(TUESDAY, "12:00"));
        var leftover = references.add(startedBooking.id(), upload(staff), staff);
        timeIsAfterStartOf(started, Duration.ofMinutes(30));
        clients.erase(anna);
        entityManager.flush();
        entityManager.clear();

        for (var booking : List.of(startedBooking, future)) {
            var file = upload(staff);
            assertThatThrownBy(() -> references.add(booking.id(), file, staff))
                    .isInstanceOfSatisfying(
                            ValidationException.class,
                            e -> assertThat(e.field()).isEqualTo("bookingId"));
        }
        // Удалять сотрудник может и у обезличенного: вдруг что-то осталось.
        references.remove(startedBooking.id(), leftover.fileId(), staff);
        assertThat(references.list(startedBooking.id(), master)).isEmpty();
    }

    @Test
    void mergeMovesReferencesWithTheBooking() {
        var card = client("Мария");
        var booking = bookings.createByMaster(manicure(card, slot(MONDAY, "12:00")), false, master);
        var staff = signedInMaster();
        var reference = references.add(booking.id(), upload(staff), staff);
        var registered = clients.register(new RegisterClient(UUID.randomUUID(), "Maria", null, null))
                .id();

        clients.merge(card, registered, master);

        assertThat(references.list(booking.id(), signedIn(registered))).containsExactly(reference);
    }

    /** Вторая активная запись того же клиента: себе клиент больше одной не заводит. */
    private BookingView byMaster(UUID clientId, SlotView slot) {
        return bookings.createByMaster(manicure(clientId, slot), false, master);
    }

    private UUID upload(Actor uploader) {
        return ReferenceUploads.upload(files, uploader);
    }
}
