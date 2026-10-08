package gag.nails.booking.internal.settings;

import gag.nails.booking.command.UpdateBookingSettings;
import gag.nails.booking.model.BookingSettingsView;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(schema = "booking", name = "settings")
public class BookingSettingsEntity {

    @Id
    private UUID id;

    private int maxActiveBookings;
    private int bookingCutoffMinutes;
    private int prepaymentHoldHours;
    private int freeCancelWindowHours;
    private int prepaymentPercent;
    private int lateArrivalToleranceMinutes;
    private int markCorrectionWindowDays;

    // Аудит, не бизнес-время: бизнес-время берётся только из Clock.
    @UpdateTimestamp
    private Instant updatedAt;

    protected BookingSettingsEntity() {}

    void apply(UpdateBookingSettings command) {
        this.maxActiveBookings = command.maxActiveBookings();
        this.bookingCutoffMinutes = command.bookingCutoffMinutes();
        this.prepaymentHoldHours = command.prepaymentHoldHours();
        this.freeCancelWindowHours = command.freeCancelWindowHours();
        this.prepaymentPercent = command.prepaymentPercent();
        this.lateArrivalToleranceMinutes = command.lateArrivalToleranceMinutes();
        this.markCorrectionWindowDays = command.markCorrectionWindowDays();
    }

    public BookingSettingsView toView() {
        return new BookingSettingsView(
                maxActiveBookings,
                bookingCutoffMinutes,
                prepaymentHoldHours,
                freeCancelWindowHours,
                prepaymentPercent,
                lateArrivalToleranceMinutes,
                markCorrectionWindowDays);
    }
}
