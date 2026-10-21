package gag.nails.booking.internal.reference;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.booking.BookingReferences;
import gag.nails.booking.BookingService;
import gag.nails.booking.command.CreateBooking;
import gag.nails.clients.ClientService;
import gag.nails.clients.command.CreateClient;
import gag.nails.common.actor.Actor;
import gag.nails.files.FileStorage;
import gag.nails.files.model.FilePurpose;
import gag.nails.schedule.ScheduleService;
import gag.nails.testsupport.ModuleTest;
import gag.nails.testsupport.MutableClock;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Удаление референсов по сроку пачками: размер пачки здесь 2, поэтому три записи уходят за две пачки и пустую. */
@ModuleTest
class ReferenceBatchesTest {

    private static final UUID MANICURE = UUID.fromString("019a0c00-0000-7000-8000-000000000001");

    private final Actor master = Actor.of(UUID.randomUUID(), null, Actor.masterAccess());

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
    MutableClock clock;

    @Test
    void expiredReferencesAreRemovedInSeveralBatches() {
        var retention = new ReferenceRetention(references, removal, transaction, 2);
        var added = new ArrayList<UUID>();
        for (var day = 0; day < 3; day++) {
            var booking =
                    cancelledBookingWithReference(LocalDate.of(2026, 10, 5).plusDays(day), added);
            assertThat(bookingReferences.list(booking, master)).hasSize(1);
        }

        assertThat(retention.removeExpired(clock.instant().plus(Duration.ofDays(1))))
                .isEqualTo(3);

        assertThat(added).allSatisfy(fileId -> {
            assertThat(references.existsById(fileId)).isFalse();
            assertThat(files.find(fileId)).isEmpty();
        });
    }

    private UUID cancelledBookingWithReference(LocalDate day, List<UUID> added) {
        var client = clients.create(new CreateClient("Анна", null, null, null, null, null), master)
                .id();
        var slot = schedule.addSlot(day, LocalTime.NOON, master);
        var booking = bookings.createByMaster(
                        new CreateBooking(client, slot.id(), MANICURE, List.of(), null), false, master)
                .id();
        added.add(bookingReferences.add(booking, upload(), master).fileId());
        bookings.cancel(booking, master);
        return booking;
    }

    private UUID upload() {
        var out = new ByteArrayOutputStream();
        try {
            ImageIO.write(new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB), "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var content = out.toByteArray();
        return files.store(new ByteArrayInputStream(content), content.length, FilePurpose.REFERENCE, master)
                .id();
    }
}
