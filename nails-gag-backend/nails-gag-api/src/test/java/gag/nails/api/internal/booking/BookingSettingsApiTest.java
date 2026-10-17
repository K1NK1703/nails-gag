package gag.nails.api.internal.booking;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import gag.nails.booking.BookingSettingsService;
import gag.nails.booking.command.UpdateBookingSettings;
import gag.nails.booking.model.BookingSettingsChangeView;
import gag.nails.booking.model.BookingSettingsView;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient.RequestHeadersSpec;
import tools.jackson.databind.JsonNode;

/**
 * Правила записи (спецификация API, §7.6): текущие — любому вошедшему, изменение и журнал — по разделу «Правила
 * записи», его проверяет модуль. Правила в базе одни на всех: тесты, которые их меняют, возвращают прежние значения.
 */
@ApiTest
class BookingSettingsApiTest extends ApiTestSupport {

    /** Наибольшее допустимое опоздание в минутах — {@code @Max} у {@link UpdateBookingSettings}. */
    private static final int MAX_TOLERANCE_MINUTES = 120;

    @Autowired
    BookingSettingsService bookingSettings;

    @Test
    void settingsReadableBySession() {
        var current = bookingSettings.settings();

        expectProblem(client.get().uri("/api/booking-settings"), HttpStatus.UNAUTHORIZED, "common.unauthenticated");
        var read = json(as(signInClient(), client.get().uri("/api/booking-settings")));

        assertThat(field(read, "maxActiveBookings").asInt()).isEqualTo(current.maxActiveBookings());
        assertThat(field(read, "bookingCutoffMinutes").asInt()).isEqualTo(current.bookingCutoffMinutes());
        assertThat(field(read, "prepaymentHoldHours").asInt()).isEqualTo(current.prepaymentHoldHours());
        assertThat(field(read, "freeCancelWindowHours").asInt()).isEqualTo(current.freeCancelWindowHours());
        assertThat(field(read, "prepaymentPercent").asInt()).isEqualTo(current.prepaymentPercent());
        assertThat(field(read, "lateArrivalToleranceMinutes").asInt()).isEqualTo(current.lateArrivalToleranceMinutes());
        assertThat(field(read, "markCorrectionWindowDays").asInt()).isEqualTo(current.markCorrectionWindowDays());
    }

    @Test
    void masterUpdatesSettings() {
        var current = bookingSettings.settings();
        var tolerance = otherTolerance(current.lateArrivalToleranceMinutes());
        try {
            var updated = json(updateSettings(signInMaster(), withTolerance(current, tolerance)));

            assertThat(field(updated, "lateArrivalToleranceMinutes").asInt()).isEqualTo(tolerance);
            assertThat(field(updated, "maxActiveBookings").asInt()).isEqualTo(current.maxActiveBookings());
            assertThat(bookingSettings.settings().lateArrivalToleranceMinutes()).isEqualTo(tolerance);
        } finally {
            restore(current);
        }
    }

    @Test
    void clientCannotUpdateSettings() {
        var current = bookingSettings.settings();
        var tolerance = otherTolerance(current.lateArrivalToleranceMinutes());
        try {
            expectProblem(
                    updateSettings(signInClient(), withTolerance(current, tolerance)),
                    HttpStatus.NOT_FOUND,
                    "common.not_found");
            assertThat(bookingSettings.settings()).isEqualTo(current);
        } finally {
            restore(current);
        }
    }

    /**
     * Журнал общий для всей базы, а часы в тестах стоят: такая же строка могла остаться от прошлых тестов и прогонов,
     * поэтому проверяется, что совпадающих строк стало на одну больше. До и после считаются одним условием.
     */
    @Test
    void settingsChangeAppearsInHistory() {
        var current = bookingSettings.settings();
        var oldValue = current.lateArrivalToleranceMinutes();
        var newValue = otherTolerance(oldValue);
        var before = bookingSettings.history(developerActor()).stream()
                .filter(change -> isToleranceChange(change, oldValue, newValue))
                .count();
        try {
            bookingSettings.update(withTolerance(current, newValue), developerActor());

            var history = json(as(signInMaster(), client.get().uri("/api/booking-settings/history")));

            var matching = history.values().stream()
                    .map(BookingSettingsApiTest::change)
                    .filter(change -> isToleranceChange(change, oldValue, newValue))
                    .count();
            assertThat(matching).isEqualTo(before + 1);
        } finally {
            restore(current);
        }
    }

    private RequestHeadersSpec<?> updateSettings(Session session, UpdateBookingSettings command) {
        return as(session, csrf(client.put().uri("/api/booking-settings")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(command);
    }

    /** Прежние правила; если ничего не менялось, журнал не пополняется. */
    private void restore(BookingSettingsView settings) {
        bookingSettings.update(withTolerance(settings, settings.lateArrivalToleranceMinutes()), developerActor());
    }

    /** Те же правила с другим допустимым опозданием. */
    private static UpdateBookingSettings withTolerance(BookingSettingsView settings, int toleranceMinutes) {
        return new UpdateBookingSettings(
                settings.maxActiveBookings(),
                settings.bookingCutoffMinutes(),
                settings.prepaymentHoldHours(),
                settings.freeCancelWindowHours(),
                settings.prepaymentPercent(),
                toleranceMinutes,
                settings.markCorrectionWindowDays());
    }

    /** Другое допустимое опоздание: на 5 минут больше текущего, у наибольшего — меньше. */
    private static int otherTolerance(int current) {
        return current < MAX_TOLERANCE_MINUTES ? current + 5 : current - 5;
    }

    /** Строка журнала из ответа; отсутствующее поле — провал теста. */
    private static BookingSettingsChangeView change(JsonNode entry) {
        return new BookingSettingsChangeView(
                field(entry, "field").asString(),
                field(entry, "oldValue").asString(),
                field(entry, "newValue").asString(),
                Instant.parse(field(entry, "changedAt").asString()));
    }

    private static boolean isToleranceChange(BookingSettingsChangeView change, int oldValue, int newValue) {
        return change.field().equals("late_arrival_tolerance_minutes")
                && change.oldValue().equals(String.valueOf(oldValue))
                && change.newValue().equals(String.valueOf(newValue));
    }
}
