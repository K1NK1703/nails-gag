package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.booking.command.CreateBooking;
import gag.nails.booking.model.BookedServiceView;
import gag.nails.booking.model.BookingStatus;
import gag.nails.booking.model.BookingView;
import gag.nails.booking.model.MarkResult;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.InternalException;
import gag.nails.common.error.ValidationException;
import gag.nails.schedule.model.SlotView;
import gag.nails.testsupport.ModuleTest;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Пакетная отметка «выполнены по прайсу» ({@link VisitMarks#completeAsPlanned}): спецификация финансов §3.2. На
 * настоящих транзакциях — каждая запись отмечается в своей транзакции, внутри чужой метод не работает, поэтому тест
 * фиксирует свои данные и удаляет после себя только их ({@link CommittedData}).
 */
@ModuleTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BatchMarkTest extends BookingTestSupport {

    /** Имя триггера и его функции в схеме booking: ставится одним тестом и снимается после каждого. */
    private static final String FAILURE_TRIGGER = "batch_mark_test_fail_completion";

    @Autowired
    VisitMarks marks;

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
    void completesEachAsPlanned() {
        var anna = client("Анна");
        var morning = slot(MONDAY, "10:00");
        var withFrench = bookings.createByClient(
                new CreateBooking(anna, morning.id(), MANICURE, List.of(FRENCH), null), Actor.client(anna));
        var maria = client("Мария");
        var afternoon = slot(MONDAY, "14:00");
        var plain = book(maria, afternoon);
        timeIsAfterStartOf(afternoon, Duration.ofHours(2));

        var results = marks.completeAsPlanned(List.of(withFrench.id(), plain.id()), master);

        assertThat(results).containsExactly(new MarkResult(withFrench.id(), null), new MarkResult(plain.id(), null));
        var first = queries.get(withFrench.id(), master);
        assertThat(first.status()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(first.completedAt()).isEqualTo(clock.instant());
        assertThat(first.actualServices())
                .extracting(BookedServiceView::serviceId)
                .containsExactly(MANICURE, FRENCH);
        assertThat(first.finalAmount()).isEqualByComparingTo("2800.00");
        assertThat(first.amountNote()).isNull();
        var second = queries.get(plain.id(), master);
        assertThat(second.status()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(second.finalAmount()).isEqualByComparingTo("2500.00");
    }

    @Test
    void reportsPerBookingErrors() {
        var started = book(client("Анна"), slot(MONDAY, "10:00"));
        var afternoon = slot(MONDAY, "14:00");
        var alreadyCompleted = book(client("Мария"), afternoon);
        var notStarted = book(client("Ольга"), slot(TUESDAY, "12:00"));
        var missing = UUID.randomUUID();
        timeIsAfterStartOf(afternoon, Duration.ofHours(2));
        marks.complete(alreadyCompleted.id(), master);

        var results =
                marks.completeAsPlanned(List.of(notStarted.id(), alreadyCompleted.id(), missing, started.id()), master);

        assertThat(results)
                .containsExactly(
                        new MarkResult(notStarted.id(), "booking.visit_not_started"),
                        new MarkResult(alreadyCompleted.id(), "booking.invalid_transition"),
                        new MarkResult(missing, "booking.booking_not_found"),
                        new MarkResult(started.id(), null));
        assertThat(queries.get(notStarted.id(), master).status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(queries.get(started.id(), master).status()).isEqualTo(BookingStatus.COMPLETED);
    }

    /**
     * Сбой средней записи не откатывает соседние, и её собственные изменения не остаются. Средняя запись падает уже
     * после изменения строки — при фиксации, на триггере, который тест ставит только на неё: если бы записи шли в
     * одной транзакции, не зафиксировалась бы ни одна (или зафиксировалась бы и половина средней).
     */
    @Test
    void oneFailureDoesNotRollBackOthers() {
        var first = book(client("Анна"), slot(MONDAY, "10:00"));
        var failing = book(client("Мария"), slot(MONDAY, "12:00"));
        var afternoon = slot(MONDAY, "14:00");
        var last = book(client("Ольга"), afternoon);
        timeIsAfterStartOf(afternoon, Duration.ofHours(2));
        failCompletionOf(failing);

        var results = marks.completeAsPlanned(List.of(first.id(), failing.id(), last.id()), master);

        assertThat(results).extracting(MarkResult::errorCode).containsExactly(null, "common.internal", null);
        assertThat(committedStatus(first)).isEqualTo("COMPLETED");
        assertThat(committedStatus(failing)).isEqualTo("CONFIRMED");
        assertThat(committedServiceCount(failing)).isEqualTo(1);
        assertThat(committedStatus(last)).isEqualTo("COMPLETED");
    }

    @Test
    void moreThanFiftyIsRejected() {
        var fiftyOne = Stream.generate(UUID::randomUUID).limit(51).toList();

        assertThatThrownBy(() -> marks.completeAsPlanned(fiftyOne, master))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).field()).isEqualTo("bookingIds"));

        // Ровно пятьдесят — можно: каждая запись получает свой результат.
        assertThat(marks.completeAsPlanned(fiftyOne.subList(0, 50), master))
                .hasSize(50)
                .extracting(MarkResult::errorCode)
                .containsOnly("booking.booking_not_found");
    }

    @Test
    void duplicateIdsAreMarkedOnce() {
        var anna = book(client("Анна"), slot(MONDAY, "10:00"));
        var afternoon = slot(MONDAY, "14:00");
        var maria = book(client("Мария"), afternoon);
        timeIsAfterStartOf(afternoon, Duration.ofHours(2));

        var results = marks.completeAsPlanned(List.of(anna.id(), anna.id(), maria.id(), anna.id()), master);

        assertThat(results).containsExactly(new MarkResult(anna.id(), null), new MarkResult(maria.id(), null));
        assertThat(queries.get(anna.id(), master).status()).isEqualTo(BookingStatus.COMPLETED);
    }

    @Test
    void needsBookingsWrite() {
        var anna = client("Анна");
        var slot = slot(MONDAY, "10:00");
        var booking = book(anna, slot);
        timeIsAfterStartOf(slot, Duration.ofHours(2));
        var reader = Actor.of(null, null, Map.of(Section.BOOKINGS, AccessLevel.READ));

        assertThatThrownBy(() -> marks.completeAsPlanned(List.of(booking.id()), Actor.client(anna)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> marks.completeAsPlanned(List.of(booking.id()), reader))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(queries.get(booking.id(), master).status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    /** Каждая запись — своя транзакция: внутри чужой сбой одной записи откатил бы и остальные. */
    @Test
    void refusesToRunInsideTransaction() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(
                        status -> marks.completeAsPlanned(List.of(UUID.randomUUID()), master)))
                .isInstanceOf(InternalException.class);
    }

    /**
     * Триггер, который отвергает отметку «выполнена» одной записи: сбой при фиксации, после изменения строки. Только
     * для этой записи и только на время теста ({@link #dropFailureTrigger}).
     */
    private void failCompletionOf(BookingView booking) {
        jdbc.sql("""
                CREATE FUNCTION booking.%s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.id = '%s' AND NEW.status = 'COMPLETED' THEN
                        RAISE EXCEPTION 'completion rejected by test';
                    END IF;
                    RETURN NEW;
                END $$""".formatted(FAILURE_TRIGGER, booking.id())).update();
        jdbc.sql("CREATE TRIGGER %1$s BEFORE UPDATE ON booking.booking FOR EACH ROW EXECUTE FUNCTION booking.%1$s()"
                        .formatted(FAILURE_TRIGGER))
                .update();
    }

    @AfterEach
    void dropFailureTrigger() {
        jdbc.sql("DROP TRIGGER IF EXISTS %1$s ON booking.booking".formatted(FAILURE_TRIGGER))
                .update();
        jdbc.sql("DROP FUNCTION IF EXISTS booking.%s()".formatted(FAILURE_TRIGGER))
                .update();
    }

    private int committedServiceCount(BookingView booking) {
        return jdbc.sql("select count(*) from booking.booking_service where booking_id = :id")
                .param("id", booking.id())
                .query(Integer.class)
                .single();
    }

    /** Состояние записи, как его видит другая транзакция: прямо из базы, мимо контекста JPA. */
    private String committedStatus(BookingView booking) {
        return jdbc.sql("select status from booking.booking where id = :id")
                .param("id", booking.id())
                .query(String.class)
                .single();
    }
}
