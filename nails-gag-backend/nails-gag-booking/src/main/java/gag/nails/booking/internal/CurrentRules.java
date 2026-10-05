package gag.nails.booking.internal;

import java.time.Clock;
import org.springframework.stereotype.Component;

/** Правила на текущий момент: действующие настройки и время из {@code Clock}. */
@Component
class CurrentRules {

    private final BookingSettingsRepository settings;
    private final Clock clock;

    CurrentRules(BookingSettingsRepository settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    BookingRules get() {
        return new BookingRules(settings.single().toView(), clock.instant());
    }
}
