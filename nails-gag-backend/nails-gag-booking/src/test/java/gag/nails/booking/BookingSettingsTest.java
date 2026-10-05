package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.testsupport.ModuleTest;
import gag.nails.testsupport.TestTimeConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ModuleTest
class BookingSettingsTest {

    private static final BookingSettingsView DEFAULTS = new BookingSettingsView(1, 120, 24, 24, 50, 15, 7);

    @Autowired
    BookingSettingsService settings;

    private final Actor master = Actor.master();

    @Test
    void defaultsComeFromTheOffer() {
        assertThat(settings.settings()).isEqualTo(DEFAULTS);
    }

    @Test
    void masterUpdatesRulesAndEachChangedFieldIsJournaled() {
        var updated = settings.update(new UpdateBookingSettings(2, 120, 24, 48, 50, 15, 7), master);

        assertThat(updated).isEqualTo(new BookingSettingsView(2, 120, 24, 48, 50, 15, 7));
        assertThat(settings.settings()).isEqualTo(updated);
        assertThat(settings.history(master))
                .containsExactlyInAnyOrder(
                        new BookingSettingsChangeView("max_active_bookings", "1", "2", TestTimeConfiguration.START),
                        new BookingSettingsChangeView(
                                "free_cancel_window_hours", "24", "48", TestTimeConfiguration.START));
    }

    @Test
    void updateWithoutChangesAddsNoJournalEntries() {
        settings.update(new UpdateBookingSettings(1, 120, 24, 24, 50, 15, 7), master);

        assertThat(settings.history(master)).isEmpty();
    }

    @Test
    void outOfRangeValueIsRejected() {
        assertThatThrownBy(() -> settings.update(new UpdateBookingSettings(0, 120, 24, 24, 50, 15, 7), master))
                .isInstanceOf(ValidationException.class)
                .satisfies(e ->
                        assertThat(((ValidationException) e).params()).containsEntry("field", "maxActiveBookings"));
    }

    @Test
    void clientCannotChangeRulesOrReadHistory() {
        var client = Actor.client(UUID.randomUUID());

        assertThatThrownBy(() -> settings.update(new UpdateBookingSettings(2, 120, 24, 24, 50, 15, 7), client))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> settings.history(client)).isInstanceOf(AccessDeniedException.class);
    }
}
