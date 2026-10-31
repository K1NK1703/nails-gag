package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.booking.command.CreateBooking;
import gag.nails.booking.command.UpdateBookingSettings;
import gag.nails.booking.command.UpdateVisit;
import gag.nails.booking.internal.booking.BookingRules;
import gag.nails.booking.model.BookingView;
import gag.nails.catalog.CatalogService;
import gag.nails.catalog.command.CreateService;
import gag.nails.catalog.model.ServiceKind;
import gag.nails.common.actor.Actor;
import gag.nails.schedule.model.SlotView;
import gag.nails.testsupport.ModuleTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Миграция {@code booking/0005-finance.sql} (спецификация финансов §3.1): заполнение суммы уже полученных предоплат
 * и ограничения новых колонок.
 *
 * <p>Строку «как до миграции» в общей базе тестов не завести: миграция уже применена, а ограничения не дают
 * полученной предоплате остаться без суммы. Поэтому тест выполняет тот самый оператор заполнения — {@code UPDATE} из
 * набора изменений {@value #BACKFILL_CHANGESET}, прочитанный из файла миграции, — на записях, у которых сумма
 * заменена заведомо неверной, и сверяет результат с правилом модуля ({@link BookingRules#defaultPrepaymentAmount}).
 * Всё — в транзакции теста с откатом.
 */
@ModuleTest
class PrepaymentAmountMigrationTest extends BookingTestSupport {

    private static final String MIGRATION = "db/changelog/booking/0005-finance.sql";
    private static final String BACKFILL_CHANGESET = "nails-gag:booking-0005-prepayment-amount";
    private static final BigDecimal WRONG = new BigDecimal("999999.00");

    @Autowired
    BookingPrepayments prepayments;

    @Autowired
    BookingSettingsService settings;

    @Autowired
    VisitMarks marks;

    @Autowired
    CatalogService catalog;

    @Autowired
    JdbcClient jdbc;

    @PersistenceContext
    EntityManager entityManager;

    /** Цена выбранного при записи состава, процент предоплаты и ожидаемая сумма. */
    @ParameterizedTest(name = "{1} % от {0} = {2}")
    @CsvSource({
        "2500.00, 50, 1250.00",
        "2501.00, 50, 1251.00",
        // половина — вверх: при «банковском» округлении было бы 2 и 24
        "250.00, 1, 3.00",
        "2450.00, 1, 25.00",
        "150.00, 1, 2.00",
        "333.33, 30, 100.00",
        "199.99, 100, 200.00",
        // мелкие суммы — не меньше рубля
        "49.99, 1, 1.00",
        "0.80, 50, 1.00",
        "0.00, 100, 1.00"
    })
    void backfillMatchesModuleRule(String price, int percent, String expected) throws IOException {
        settings.update(new UpdateBookingSettings(1, 120, 24, 24, percent, 15, 7), master);
        var booking = receivedByDefault("Анна", slot(MONDAY, "10:00"), service(ServiceKind.MAIN, price));
        var rule =
                new BookingRules(settings.settings(), clock.instant()).defaultPrepaymentAmount(new BigDecimal(price));
        assertThat(rule).isEqualByComparingTo(expected);
        entityManager.flush();
        assertThat(storedAmount(booking)).isEqualByComparingTo(expected);

        spoil(booking);
        jdbc.sql(backfillStatement()).update();

        assertThat(storedAmount(booking)).isEqualByComparingTo(expected);
    }

    /** Сумма — от выбранного при записи состава, а не от фактического и не от итога визита. */
    @Test
    void backfillUsesPlannedServices() throws IOException {
        var slot = slot(MONDAY, "14:00");
        var completed = receivedByDefault("Ольга", slot, MANICURE, FRENCH);
        var withoutPrepayment = book(client("Ирина"), slot(MONDAY, "16:00"));
        timeIsAfterStartOf(slot, Duration.ofHours(2));
        marks.complete(completed.id(), master);
        marks.updateVisit(completed.id(), new UpdateVisit(MANICURE, List.of(), new BigDecimal("500.00"), null), master);
        entityManager.flush();
        spoil(completed);

        jdbc.sql(backfillStatement()).update();

        assertThat(storedAmount(completed)).isEqualByComparingTo("1400.00");
        assertThat(storedAmount(withoutPrepayment)).isNull();
    }

    /** Записи без выбранного состава быть не должно; если она есть, прайс — ноль, и сумма — рубль, как у модуля. */
    @Test
    void backfillOfBookingWithoutPlannedServicesIsOneRuble() throws IOException {
        var booking = receivedByDefault("Анна", slot(MONDAY, "10:00"), MANICURE);
        entityManager.flush();
        jdbc.sql("delete from booking.booking_service where booking_id = :id")
                .param("id", booking.id())
                .update();
        spoil(booking);

        jdbc.sql(backfillStatement()).update();

        assertThat(storedAmount(booking)).isEqualByComparingTo("1.00");
        assertThat(new BookingRules(settings.settings(), clock.instant()).defaultPrepaymentAmount(BigDecimal.ZERO))
                .isEqualByComparingTo("1.00");
    }

    /** Полученная предоплата всегда с суммой, неполученная — без неё. */
    @Test
    void receivedPrepaymentAlwaysHasAmount() {
        var booking = receivedByDefault("Анна", slot(MONDAY, "10:00"), MANICURE);
        entityManager.flush();

        assertThatThrownBy(() -> jdbc.sql("update booking.booking set prepayment_amount = null where id = :id")
                        .param("id", booking.id())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("booking_prepayment_amount_received_check");
    }

    @Test
    void amountNoteOnlyOnCompletedVisit() {
        var booking = book(client("Анна"), slot(MONDAY, "10:00"));
        entityManager.flush();

        assertThatThrownBy(() -> jdbc.sql("update booking.booking set amount_note = 'подарок' where id = :id")
                        .param("id", booking.id())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("booking_amount_note_completed_check");
    }

    @Test
    void outcomeOverrideOnlyOnCancelledOrNoShow() {
        var booking = receivedByDefault("Анна", slot(MONDAY, "10:00"), MANICURE);
        entityManager.flush();

        assertThatThrownBy(
                        () -> jdbc.sql("update booking.booking set prepayment_outcome_override = 'KEPT' where id = :id")
                                .param("id", booking.id())
                                .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("booking_prepayment_outcome_override_status_check");
    }

    private static String backfillStatement() throws IOException {
        return MigrationStatements.bookingUpdate(MIGRATION, BACKFILL_CHANGESET);
    }

    /** Сумма заведомо неверная: заполнение должно её пересчитать. */
    private void spoil(BookingView booking) {
        jdbc.sql("update booking.booking set prepayment_amount = :wrong where id = :id")
                .param("wrong", WRONG)
                .param("id", booking.id())
                .update();
    }

    private BookingView receivedByDefault(String clientName, SlotView slot, UUID main, UUID... addons) {
        var clientId = clientOwingPrepayment(clientName);
        var booking = bookings.createByClient(
                new CreateBooking(clientId, slot.id(), main, List.of(addons), null), Actor.client(clientId));
        return prepayments.markReceived(booking.id(), master);
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

    private @Nullable BigDecimal storedAmount(BookingView booking) {
        var values = jdbc.sql("select prepayment_amount from booking.booking where id = :id")
                .param("id", booking.id())
                .query(BigDecimal.class)
                .list();
        assertThat(values).hasSize(1);
        return values.getFirst();
    }
}
