package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.booking.command.CreateBooking;
import gag.nails.booking.command.UpdateVisit;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Заполнение суммы уже полученных предоплат миграцией {@code booking/0005-finance.sql} (спецификация финансов §3.1).
 *
 * <p>Строку «как до миграции» в общей базе тестов не завести: миграция уже применена, а ограничения не дают
 * полученной предоплате остаться без суммы. Поэтому тест выполняет тот самый оператор заполнения — текст набора
 * изменений {@value #BACKFILL_CHANGESET}, прочитанный из файла миграции, — на записях, у которых сумма заменена
 * заведомо неверной, и сверяет результат с правилом модуля. Всё — в транзакции теста с откатом.
 */
@ModuleTest
class PrepaymentAmountMigrationTest extends BookingTestSupport {

    private static final String MIGRATION = "db/changelog/booking/0005-finance.sql";
    private static final String BACKFILL_CHANGESET = "nails-gag:booking-0005-prepayment-amount";
    private static final BigDecimal WRONG = new BigDecimal("999999.00");

    @Autowired
    BookingPrepayments prepayments;

    @Autowired
    VisitMarks marks;

    @Autowired
    CatalogService catalog;

    @Autowired
    JdbcClient jdbc;

    @PersistenceContext
    EntityManager entityManager;

    @Test
    void backfillTakesPercentOfPlannedPriceRoundedHalfUpToRuble() throws IOException {
        // 50 % от 2501,00 = 1250,50 → 1251; 50 % от 0,80 = 0,40 → 0 → не меньше рубля.
        var halfUp = receivedByDefault("Анна", "10:00", MANICURE, service(ServiceKind.ADDON, "1.00"));
        var atLeastRuble = receivedByDefault("Мария", "12:00", service(ServiceKind.MAIN, "0.80"));
        // Выполненный визит с другим составом и итогом: сумма — от выбранного при записи состава.
        var completedSlot = slot(MONDAY, "14:00");
        var completed = receivedByDefault("Ольга", completedSlot, MANICURE, FRENCH);
        var withoutPrepayment = book(client("Ирина"), slot(MONDAY, "16:00"));
        timeIsAfterStartOf(completedSlot, Duration.ofHours(2));
        marks.complete(completed.id(), master);
        marks.updateVisit(completed.id(), new UpdateVisit(MANICURE, List.of(), new BigDecimal("500.00"), null), master);
        var received = List.of(halfUp, atLeastRuble, completed);
        entityManager.flush();
        entityManager.clear();
        // Правило модуля дало ту же сумму, что ожидается от миграции.
        assertThat(storedAmount(halfUp)).isEqualByComparingTo("1251.00");
        assertThat(storedAmount(atLeastRuble)).isEqualByComparingTo("1.00");
        assertThat(storedAmount(completed)).isEqualByComparingTo("1400.00");
        for (var booking : received) {
            jdbc.sql("update booking.booking set prepayment_amount = :wrong where id = :id")
                    .param("wrong", WRONG)
                    .param("id", booking.id())
                    .update();
        }

        jdbc.sql(backfillStatement()).update();

        assertThat(storedAmount(halfUp)).isEqualByComparingTo("1251.00");
        assertThat(storedAmount(atLeastRuble)).isEqualByComparingTo("1.00");
        assertThat(storedAmount(completed)).isEqualByComparingTo("1400.00");
        assertThat(storedAmount(withoutPrepayment)).isNull();
    }

    /** Ограничение миграции: полученная предоплата всегда с суммой, неполученная — без неё. */
    @Test
    void receivedPrepaymentAlwaysHasAmount() {
        var booking = receivedByDefault("Анна", "10:00", MANICURE);
        entityManager.flush();

        assertThatThrownBy(() -> jdbc.sql("update booking.booking set prepayment_amount = null where id = :id")
                        .param("id", booking.id())
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Текст набора изменений заполнения из файла миграции: строки до следующего набора, без комментариев. */
    private static String backfillStatement() throws IOException {
        var lines = new ClassPathResource(MIGRATION)
                .getContentAsString(StandardCharsets.UTF_8)
                .lines()
                .toList();
        var statement = new ArrayList<String>();
        var inside = false;
        for (var line : lines) {
            if (line.startsWith("--changeset ")) {
                inside = line.equals("--changeset " + BACKFILL_CHANGESET);
            } else if (inside && !line.startsWith("--")) {
                statement.add(line);
            }
        }
        var sql = String.join("\n", statement).strip();
        assertThat(sql).as("changeset " + BACKFILL_CHANGESET).startsWith("UPDATE booking.booking");
        return sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
    }

    private BookingView receivedByDefault(String clientName, String time, UUID main, UUID... addons) {
        return receivedByDefault(clientName, slot(MONDAY, time), main, addons);
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
