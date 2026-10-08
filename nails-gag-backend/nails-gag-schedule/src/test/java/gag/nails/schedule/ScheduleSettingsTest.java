package gag.nails.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.schedule.model.SettingsChangeView;
import gag.nails.testsupport.ModuleTest;
import gag.nails.testsupport.MutableClock;
import gag.nails.testsupport.TestTimeConfiguration;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ModuleTest
class ScheduleSettingsTest {

    @Autowired
    ScheduleSettingsService settings;

    @Autowired
    MutableClock clock;

    private final Actor master = Actor.master();

    @Test
    void defaultSlotDurationIsTwoHours() {
        assertThat(settings.settings().slotDurationMinutes()).isEqualTo(120);
    }

    @Test
    void masterChangesSlotDurationAndChangeIsJournaled() {
        var changed = settings.setSlotDuration(90, master);

        assertThat(changed.slotDurationMinutes()).isEqualTo(90);
        assertThat(settings.settings().slotDurationMinutes()).isEqualTo(90);
        assertThat(settings.history(master))
                .containsExactly(
                        new SettingsChangeView("slot_duration_minutes", "120", "90", TestTimeConfiguration.START));
    }

    @Test
    void historyListsNewestFirst() {
        settings.setSlotDuration(90, master);
        clock.advance(Duration.ofMinutes(1));
        settings.setSlotDuration(100, master);

        assertThat(settings.history(master))
                .extracting(SettingsChangeView::newValue)
                .containsExactly("100", "90");
    }

    @Test
    void settingTheSameValueAddsNoJournalEntry() {
        settings.setSlotDuration(120, master);

        assertThat(settings.history(master)).isEmpty();
    }

    @Test
    void durationOutOfRangeIsRejected() {
        assertThatThrownBy(() -> settings.setSlotDuration(10, master))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "minutes"));
    }

    @Test
    void clientCannotChangeOrReadHistory() {
        var client = Actor.client(UUID.randomUUID());

        assertThatThrownBy(() -> settings.setSlotDuration(90, client)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> settings.history(client)).isInstanceOf(AccessDeniedException.class);
    }
}
