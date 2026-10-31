package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.booking.command.CreateBooking;
import gag.nails.booking.command.UpdateBookingSettings;
import gag.nails.booking.model.BookingStatus;
import gag.nails.booking.model.BookingView;
import gag.nails.booking.model.PrepaymentOutcome;
import gag.nails.catalog.CatalogService;
import gag.nails.catalog.command.CreateService;
import gag.nails.catalog.model.ServiceKind;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.testsupport.ModuleTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Сумма полученной предоплаты: спецификация финансов §3.3. Процент предоплаты в seed — 50. */
@ModuleTest
class PrepaymentAmountTest extends BookingTestSupport {

    @Autowired
    BookingPrepayments prepayments;

    @Autowired
    BookingSettingsService settings;

    @Autowired
    CatalogService catalog;

    @PersistenceContext
    EntityManager entityManager;

    @Test
    void defaultAmountIsPercentOfPlannedPrice() {
        var withFrench = awaitingPrepayment("Анна", "10:00", MANICURE, FRENCH);

        assertThat(prepayments.markReceived(withFrench.id(), master).prepaymentAmount())
                .isEqualByComparingTo("1400.00");

        // 50 % от 2501,00 = 1250,50: половина округляется вверх, до рубля (при «банковском» округлении было бы 1250).
        var withPenny = awaitingPrepayment("Мария", "14:00", MANICURE, service(ServiceKind.ADDON, "1.00"));
        var received = prepayments.markReceived(withPenny.id(), master);
        assertThat(received.prepaymentAmount()).isEqualByComparingTo("1251.00");
        assertThat(reloaded(received).prepaymentAmount()).isEqualByComparingTo("1251.00");
    }

    @Test
    void defaultIsAtLeastOneRuble() {
        settings.update(new UpdateBookingSettings(1, 120, 24, 24, 1, 15, 7), master);
        // 1 % от 40,00 = 0,40 — округляется до нуля, но предоплата не меньше рубля.
        var cheap = awaitingPrepayment("Анна", "10:00", service(ServiceKind.MAIN, "40.00"));

        assertThat(prepayments.markReceived(cheap.id(), master).prepaymentAmount())
                .isEqualByComparingTo("1.00");
    }

    @Test
    void explicitAmountIsStored() {
        var booking = awaitingPrepayment("Анна", "10:00", MANICURE);

        var received = prepayments.markReceived(booking.id(), new BigDecimal("1000.50"), master);

        assertThat(received.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(received.prepaymentReceivedAt()).isEqualTo(clock.instant());
        assertThat(received.prepaymentAmount()).isEqualByComparingTo("1000.50");
        assertThat(reloaded(received).prepaymentAmount()).isEqualByComparingTo("1000.50");
    }

    @Test
    void amountMustBePositive() {
        var booking = awaitingPrepayment("Анна", "10:00", MANICURE);

        for (var amount : List.of("0", "-1.00", "0.001", "10000000000.00")) {
            assertThatThrownBy(() -> prepayments.markReceived(booking.id(), new BigDecimal(amount), master))
                    .as(amount)
                    .isInstanceOf(ValidationException.class)
                    .satisfies(
                            e -> assertThat(((ValidationException) e).field()).isEqualTo("amount"));
        }
        var unchanged = reloaded(booking);
        assertThat(unchanged.status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);
        assertThat(unchanged.prepaymentAmount()).isNull();

        // Наибольшая сумма numeric(12, 2) — допустима.
        assertThat(prepayments
                        .markReceived(booking.id(), new BigDecimal("9999999999.99"), master)
                        .prepaymentAmount())
                .isEqualByComparingTo("9999999999.99");
    }

    @Test
    void explicitAmountNeedsBookingsWrite() {
        var anna = clientOwingPrepayment("Анна");
        var booking = book(anna, slot(MONDAY, "10:00"));

        assertThatThrownBy(() -> prepayments.markReceived(booking.id(), new BigDecimal("500.00"), Actor.client(anna)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void viewShowsAmount() {
        var received = awaitingPrepayment("Анна", "10:00", MANICURE);
        prepayments.markReceived(received.id(), new BigDecimal("700.00"), master);
        var withoutPrepayment = book(client("Мария"), slot(MONDAY, "14:00"));

        var stored = reloaded(received);
        assertThat(stored.prepaymentAmount()).isEqualByComparingTo("700.00");
        assertThat(stored.prepaymentOutcome()).isEqualTo(PrepaymentOutcome.APPLIED);
        var other = queries.get(withoutPrepayment.id(), master);
        assertThat(other.prepaymentAmount()).isNull();
        assertThat(other.prepaymentOutcome()).isNull();
        assertThat(queries.ofDay(MONDAY, master))
                .filteredOn(view -> view.id().equals(received.id()))
                .singleElement()
                .satisfies(view -> assertThat(view.prepaymentAmount()).isEqualByComparingTo("700.00"));
    }

    /** Запись клиента с признаком обязательной предоплаты: ждёт предоплату. */
    private BookingView awaitingPrepayment(String clientName, String time, UUID main, UUID... addons) {
        var clientId = clientOwingPrepayment(clientName);
        var slot = slot(MONDAY, time);
        var booking = bookings.createByClient(
                new CreateBooking(clientId, slot.id(), main, List.of(addons), null), Actor.client(clientId));
        assertThat(booking.status()).isEqualTo(BookingStatus.AWAITING_PREPAYMENT);
        return booking;
    }

    private UUID service(ServiceKind kind, String price) {
        @Nullable Integer duration = kind == ServiceKind.MAIN ? 60 : null;
        return catalog.create(
                        new CreateService(
                                kind,
                                "Услуга " + UUID.randomUUID(),
                                "Описание",
                                new BigDecimal(price),
                                duration,
                                "Маникюр",
                                false,
                                false),
                        master)
                .id();
    }

    private BookingView reloaded(BookingView booking) {
        entityManager.flush();
        entityManager.clear();
        return queries.get(booking.id(), master);
    }
}
