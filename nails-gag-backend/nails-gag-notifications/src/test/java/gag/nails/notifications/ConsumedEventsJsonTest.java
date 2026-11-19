package gag.nails.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.booking.event.BookingCancelled;
import gag.nails.booking.event.BookingCompleted;
import gag.nails.booking.event.BookingConfirmed;
import gag.nails.booking.event.BookingCreated;
import gag.nails.booking.event.BookingNoShow;
import gag.nails.booking.event.BookingRescheduled;
import gag.nails.booking.event.EarlierTimeAccepted;
import gag.nails.booking.event.EarlierTimeDeclined;
import gag.nails.booking.event.EarlierTimeProposed;
import gag.nails.booking.event.PrepaymentRequested;
import gag.nails.booking.model.BookingCreator;
import gag.nails.booking.model.BookingStatus;
import gag.nails.booking.model.CancelReason;
import gag.nails.booking.model.RescheduleInitiator;
import gag.nails.clients.event.ClientErased;
import gag.nails.clients.event.ClientMerged;
import gag.nails.common.event.DomainEvent;
import gag.nails.identity.event.AccountDeleted;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.modulith.events.core.EventSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * События, которые слушает модуль, переживают реестр публикаций: после падения событие читается из
 * {@code modulith.event_publication} тем же {@link EventSerializer} (JSON через {@link JsonMapper} приложения), что
 * его записал, и должно получиться равным исходному. Моменты — с микросекундами, необязательные поля — и заданные, и
 * пустые.
 */
@NotificationsModuleTest
class ConsumedEventsJsonTest {

    private static final UUID BOOKING = UUID.fromString("019a8600-0000-7000-8000-00000000b001");
    private static final UUID CLIENT = UUID.fromString("019a8600-0000-7000-8000-00000000c001");
    private static final UUID OTHER_CLIENT = UUID.fromString("019a8600-0000-7000-8000-00000000c002");
    private static final UUID ACCOUNT = UUID.fromString("019a8600-0000-7000-8000-00000000a001");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("019a8600-0000-7000-8000-00000000a002");
    private static final UUID SLOT = UUID.fromString("019a8600-0000-7000-8000-00000000d001");
    private static final UUID NEW_SLOT = UUID.fromString("019a8600-0000-7000-8000-00000000d002");
    private static final Instant STARTS = Instant.parse("2026-11-21T11:00:00Z");
    private static final Instant OTHER = Instant.parse("2026-11-20T08:30:00Z");
    private static final Instant OCCURRED = Instant.parse("2026-11-15T09:41:07.123456Z");

    @Autowired
    EventSerializer serializer;

    @Autowired
    JsonMapper json;

    static Stream<DomainEvent> consumedEvents() {
        return Stream.of(
                new BookingCreated(BOOKING, CLIENT, STARTS, BookingStatus.CONFIRMED, BookingCreator.CLIENT, OCCURRED),
                new BookingCreated(
                        BOOKING, CLIENT, STARTS, BookingStatus.AWAITING_PREPAYMENT, BookingCreator.MASTER, OCCURRED),
                new PrepaymentRequested(BOOKING, CLIENT, STARTS, OTHER, OCCURRED),
                new BookingConfirmed(BOOKING, CLIENT, STARTS, true, OCCURRED),
                new BookingCancelled(BOOKING, CLIENT, STARTS, CancelReason.PREPAYMENT_TIMEOUT, OCCURRED),
                new BookingCompleted(BOOKING, CLIENT, STARTS, OCCURRED),
                new BookingNoShow(BOOKING, CLIENT, STARTS, OCCURRED),
                new BookingRescheduled(
                        BOOKING, CLIENT, SLOT, OTHER, NEW_SLOT, STARTS, OCCURRED, RescheduleInitiator.CLIENT),
                new BookingRescheduled(BOOKING, CLIENT, SLOT, OTHER, NEW_SLOT, STARTS, OCCURRED),
                new EarlierTimeProposed(BOOKING, CLIENT, STARTS, OTHER, OCCURRED),
                new EarlierTimeAccepted(BOOKING, CLIENT, STARTS, OTHER, OCCURRED),
                new EarlierTimeDeclined(BOOKING, CLIENT, STARTS, OCCURRED),
                new ClientErased(CLIENT, OCCURRED),
                new ClientMerged(OTHER_CLIENT, CLIENT, OTHER_ACCOUNT, ACCOUNT, OCCURRED),
                new ClientMerged(OTHER_CLIENT, CLIENT, OCCURRED),
                new AccountDeleted(ACCOUNT, OCCURRED));
    }

    @ParameterizedTest
    @MethodSource("consumedEvents")
    void eventSurvivesRegistryRoundTrip(DomainEvent event) {
        var stored = serializer.serialize(event);

        assertThat(serializer.deserialize(stored, event.getClass())).isEqualTo(event);
    }

    @Test
    void everyBookingEventIsCovered() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(DomainEvent.class));
        var bookingEvents = scanner.findCandidateComponents(BookingCreated.class.getPackageName()).stream()
                .map(definition -> Objects.requireNonNull(definition.getBeanClassName()))
                .toList();

        assertThat(bookingEvents).hasSize(10);
        assertThat(consumedEvents().map(event -> event.getClass().getName())).containsAll(bookingEvents);
    }

    @Test
    void rescheduleInitiatorIsStored() {
        var event = new BookingRescheduled(
                BOOKING, CLIENT, SLOT, OTHER, NEW_SLOT, STARTS, OCCURRED, RescheduleInitiator.MASTER);

        var stored = json.readTree(String.valueOf(serializer.serialize(event)));

        assertThat(stored.get("initiator").asString()).isEqualTo("MASTER");
    }

    @Test
    void rescheduleWithoutInitiatorKeyReadsAsNull() {
        var event = new BookingRescheduled(BOOKING, CLIENT, SLOT, OTHER, NEW_SLOT, STARTS, OCCURRED);
        // Публикация, записанная до появления инициатора: ключа initiator в JSON нет совсем.
        var legacy = (ObjectNode) json.readTree(String.valueOf(serializer.serialize(event)));
        legacy.remove("initiator");

        var read = serializer.deserialize(json.writeValueAsString(legacy), BookingRescheduled.class);

        assertThat(legacy.has("initiator")).isFalse();
        assertThat(read).isEqualTo(event);
        assertThat(read.initiator()).isNull();
    }
}
