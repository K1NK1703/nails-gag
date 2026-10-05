package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.testsupport.ModuleTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@ModuleTest
class BookingSeedTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void rulesFromTheOfferAreSeeded() {
        var row = jdbc.sql("""
                        select max_active_bookings, booking_cutoff_minutes, prepayment_hold_hours,
                               free_cancel_window_hours, prepayment_percent, late_arrival_tolerance_minutes,
                               mark_correction_window_days
                        from booking.settings
                        """).query().singleRow();

        assertThat(row.values()).containsExactly(1, 120, 24, 24, 50, 15, 7);
    }

    @Test
    void activeSlotIndexIsInPlace() {
        var index = jdbc.sql("select count(*) from pg_indexes where indexname = 'booking_active_slot_key'")
                .query(Integer.class)
                .single();

        assertThat(index).isEqualTo(1);
    }
}
