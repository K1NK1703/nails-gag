package gag.nails.booking.internal.reference;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.booking.BookingReferences;
import gag.nails.booking.BookingService;
import gag.nails.booking.CommittedData;
import gag.nails.booking.ReferenceUploads;
import gag.nails.booking.command.CreateBooking;
import gag.nails.clients.ClientService;
import gag.nails.clients.command.CreateClient;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.InternalException;
import gag.nails.files.FileStorage;
import gag.nails.schedule.ScheduleService;
import gag.nails.testsupport.ModuleTest;
import gag.nails.testsupport.MutableClock;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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
 * Удаление референсов по сроку пачками на настоящих транзакциях: размер пачки здесь 2, поэтому три записи уходят за
 * две пачки. Каждая пачка фиксируется сама, файлы её референсов стираются с диска после фиксации.
 */
@ModuleTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReferenceBatchesTest {

    private static final UUID MANICURE = UUID.fromString("019a0c00-0000-7000-8000-000000000001");

    private final Actor master = ReferenceUploads.signedInMaster();

    @Autowired
    ReferenceRepository references;

    @Autowired
    ReferenceRemoval removal;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    BookingReferences bookingReferences;

    @Autowired
    BookingService bookings;

    @Autowired
    ClientService clients;

    @Autowired
    ScheduleService schedule;

    @Autowired
    FileStorage files;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MutableClock clock;

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

    @Test
    void expiredReferencesAreRemovedInSeveralBatches() {
        var retention = new ReferenceRetention(references, removal, transaction, 2);
        var added = new ArrayList<UUID>();
        for (var day = 0; day < 3; day++) {
            added.add(cancelledBookingWithReference(day).fileId());
        }

        assertThat(retention.removeExpired(clock.instant().plus(Duration.ofDays(1))))
                .isEqualTo(3);

        assertThat(added).allSatisfy(this::assertRemoved);
    }

    @Test
    void failingBookingDoesNotStopTheOthers() {
        var expired = new ArrayList<Reference>();
        for (var day = 0; day < 3; day++) {
            expired.add(cancelledBookingWithReference(day));
        }
        var failing = expired.get(1);
        var retention = new ReferenceRetention(references, failingFor(failing.bookingId()), transaction, 2);

        assertThat(retention.removeExpired(clock.instant().plus(Duration.ofDays(1))))
                .isEqualTo(2);

        assertRemoved(expired.get(0).fileId());
        assertRemoved(expired.get(2).fileId());
        // Сбой откатил только свою запись: её референс и файл на месте до следующего запуска.
        assertThat(references.existsById(failing.fileId())).isTrue();
        assertThat(files.find(failing.fileId())).isPresent();
        assertThat(committed.referenceDirectory(failing.fileId())).exists();
    }

    /** Удаление, которое падает на одной записи — уже после того, как удалило референсы всей переданной пачки. */
    private ReferenceRemoval failingFor(UUID bookingId) {
        return new ReferenceRemoval(references, files) {
            @Override
            public int removeAll(Collection<UUID> bookingIds) {
                var removed = super.removeAll(bookingIds);
                if (bookingIds.contains(bookingId)) {
                    throw new InternalException("removal fails for booking " + bookingId);
                }
                return removed;
            }
        };
    }

    private void assertRemoved(UUID fileId) {
        assertThat(references.existsById(fileId)).isFalse();
        assertThat(files.find(fileId)).isEmpty();
        assertThat(committed.referenceDirectory(fileId)).doesNotExist();
    }

    private Reference cancelledBookingWithReference(int day) {
        var client = committed.client(clients.create(new CreateClient("Анна", null, null, null, null, null), master)
                .id());
        var slot = schedule.addSlot(LocalDate.of(2026, 10, 5).plusDays(day), LocalTime.NOON, master);
        committed.slot(slot.id());
        var booking = bookings.createByMaster(
                        new CreateBooking(client, slot.id(), MANICURE, List.of(), null), false, master)
                .id();
        var fileId = bookingReferences
                .add(booking, committed.file(ReferenceUploads.upload(files, master)), master)
                .fileId();
        bookings.cancel(booking, master);
        return new Reference(booking, fileId);
    }

    private record Reference(UUID bookingId, UUID fileId) {}
}
