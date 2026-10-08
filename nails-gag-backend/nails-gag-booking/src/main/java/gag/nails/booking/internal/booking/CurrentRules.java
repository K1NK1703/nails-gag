package gag.nails.booking.internal.booking;

import gag.nails.booking.internal.settings.BookingSettingsRepository;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** Правила на текущий момент: действующие настройки и время из {@code Clock}. */
@Component
public class CurrentRules {

    private final BookingSettingsRepository settings;
    private final Clock clock;

    CurrentRules(BookingSettingsRepository settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    public BookingRules get() {
        return new BookingRules(settings.single().toView(), clock.instant());
    }
}
