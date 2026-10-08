package gag.nails.booking.internal.settings;

import gag.nails.booking.BookingSettingsService;
import gag.nails.booking.command.UpdateBookingSettings;
import gag.nails.booking.model.BookingSettingsChangeView;
import gag.nails.booking.model.BookingSettingsView;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.validation.CommandValidator;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultBookingSettingsService implements BookingSettingsService {

    private final BookingSettingsRepository settings;
    private final BookingSettingsChangeRepository changes;
    private final CommandValidator validator;
    private final Clock clock;

    DefaultBookingSettingsService(
            BookingSettingsRepository settings,
            BookingSettingsChangeRepository changes,
            CommandValidator validator,
            Clock clock) {
        this.settings = settings;
        this.changes = changes;
        this.validator = validator;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public BookingSettingsView settings() {
        return settings.single().toView();
    }

    @Override
    public BookingSettingsView update(UpdateBookingSettings command, Actor actor) {
        actor.requireWrite(Section.BOOKING_SETTINGS);
        validator.requireValid(command);
        var current = settings.single();
        var before = byColumn(current.toView());
        current.apply(command);
        var after = byColumn(current.toView());
        var now = clock.instant();
        after.forEach((field, value) -> {
            var old = before.get(field);
            if (!value.equals(old)) {
                changes.save(new BookingSettingsChangeEntity(field, String.valueOf(old), String.valueOf(value), now));
            }
        });
        return current.toView();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingSettingsChangeView> history(Actor actor) {
        actor.requireRead(Section.BOOKING_SETTINGS);
        return changes.findAllByOrderByChangedAtDescIdDesc().stream()
                .map(BookingSettingsChangeEntity::toView)
                .toList();
    }

    /** Значения по именам колонок — так поле называется в журнале. */
    private static Map<String, Integer> byColumn(BookingSettingsView view) {
        var values = new LinkedHashMap<String, Integer>();
        values.put("max_active_bookings", view.maxActiveBookings());
        values.put("booking_cutoff_minutes", view.bookingCutoffMinutes());
        values.put("prepayment_hold_hours", view.prepaymentHoldHours());
        values.put("free_cancel_window_hours", view.freeCancelWindowHours());
        values.put("prepayment_percent", view.prepaymentPercent());
        values.put("late_arrival_tolerance_minutes", view.lateArrivalToleranceMinutes());
        values.put("mark_correction_window_days", view.markCorrectionWindowDays());
        return values;
    }
}
