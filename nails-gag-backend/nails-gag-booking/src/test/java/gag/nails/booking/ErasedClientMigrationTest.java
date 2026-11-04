package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.booking.model.BookingView;
import gag.nails.booking.model.CancelReason;
import gag.nails.booking.model.PrepaymentOutcome;
import gag.nails.testsupport.ModuleTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Миграция {@code booking/0006-erased-client-review.sql}: записи, которые раньше отменило само удаление аккаунта,
 * сохраняют прежний исход предоплаты — возвращена (спецификация финансов §3.3а). Тест выполняет {@code UPDATE} из
 * набора изменений {@value #CHANGESET}, прочитанный из файла миграции ({@link MigrationStatements}), на строках,
 * приведённых к виду «как до миграции». Всё — в транзакции теста с откатом.
 */
@ModuleTest
class ErasedClientMigrationTest extends BookingTestSupport {

    private static final String MIGRATION = "db/changelog/booking/0006-erased-client-review.sql";
    private static final String CHANGESET = "nails-gag:booking-0006-client-erased-at";

    @Autowired
    BookingPrepayments prepayments;

    @Autowired
    JdbcClient jdbc;

    @PersistenceContext
    EntityManager entityManager;

    @Test
    void oldAccountDeletedCancellationKeepsReturnedOutcome() throws IOException {
        var withPrepayment = received(clientOwingPrepayment("Анна"), "10:00");
        var withoutPrepayment = book(client("Мария"), slot(MONDAY, "12:00"));
        for (var booking : List.of(withPrepayment, withoutPrepayment)) {
            bookings.cancel(booking.id(), master);
            asCancelledByOldAccountDeletion(booking);
        }
        // Без переопределения правило дало бы старой отмене новый исход.
        assertThat(reloaded(withPrepayment).prepaymentOutcome()).isEqualTo(PrepaymentOutcome.KEPT);

        jdbc.sql(MigrationStatements.bookingUpdate(MIGRATION, CHANGESET)).update();

        var stored = reloaded(withPrepayment);
        assertThat(stored.cancelReason()).isEqualTo(CancelReason.ACCOUNT_DELETED);
        assertThat(stored.prepaymentOutcome()).isEqualTo(PrepaymentOutcome.RETURNED);
        assertThat(storedOverride(withPrepayment)).isEqualTo("RETURNED");
        // Без полученной предоплаты исхода нет и переопределять нечего.
        assertThat(storedOverride(withoutPrepayment)).isNull();
    }

    /** Явное решение мастера по старой отмене миграция не перезаписывает. */
    @Test
    void oldCancellationWithMasterOverrideIsNotTouched() throws IOException {
        var booking = received(clientOwingPrepayment("Анна"), "10:00");
        bookings.cancel(booking.id(), master);
        asCancelledByOldAccountDeletion(booking);
        jdbc.sql("update booking.booking set prepayment_outcome_override = 'KEPT' where id = :id")
                .param("id", booking.id())
                .update();

        jdbc.sql(MigrationStatements.bookingUpdate(MIGRATION, CHANGESET)).update();

        assertThat(storedOverride(booking)).isEqualTo("KEPT");
        assertThat(reloaded(booking).prepaymentOutcome()).isEqualTo(PrepaymentOutcome.KEPT);
    }

    @Test
    void cancellationOfBookingUnderReviewIsNotTouched() throws IOException {
        var anna = clientOwingPrepayment("Анна");
        var booking = received(anna, "10:00");
        clients.erase(anna);
        bookings.cancel(booking.id(), master);
        entityManager.flush();

        jdbc.sql(MigrationStatements.bookingUpdate(MIGRATION, CHANGESET)).update();

        var stored = reloaded(booking);
        assertThat(stored.cancelReason()).isEqualTo(CancelReason.ACCOUNT_DELETED);
        assertThat(stored.clientErasedAt()).isNotNull();
        assertThat(stored.prepaymentOutcome()).isEqualTo(PrepaymentOutcome.KEPT);
        assertThat(storedOverride(booking)).isNull();
    }

    /** Предоплата получена (50 % от 2500). */
    private BookingView received(UUID clientId, String time) {
        var booking = book(clientId, slot(MONDAY, time));
        return prepayments.markReceived(booking.id(), master);
    }

    /** Строка, какой её оставляло прежнее обезличивание: отменена с причиной ACCOUNT_DELETED, без отметки. */
    private void asCancelledByOldAccountDeletion(BookingView booking) {
        entityManager.flush();
        jdbc.sql("""
                        update booking.booking
                        set cancel_reason = 'ACCOUNT_DELETED', client_erased_at = null,
                            prepayment_outcome_override = null
                        where id = :id""").param("id", booking.id()).update();
    }

    private @Nullable String storedOverride(BookingView booking) {
        entityManager.flush();
        var values = jdbc.sql("select prepayment_outcome_override from booking.booking where id = :id")
                .param("id", booking.id())
                .query(String.class)
                .list();
        assertThat(values).hasSize(1);
        return values.getFirst();
    }

    private BookingView reloaded(BookingView booking) {
        entityManager.flush();
        entityManager.clear();
        return queries.get(booking.id(), master);
    }
}
