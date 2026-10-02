package gag.nails.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.testsupport.ModuleTest;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@ModuleTest
class ScheduleSeedTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void settingsAndEmptyRotationAreSeeded() {
        var duration = jdbc.sql("select slot_duration_minutes from schedule.settings")
                .query(Integer.class)
                .single();
        var anchor = jdbc.sql("select anchor_monday from schedule.rotation")
                .query(LocalDate.class)
                .single();
        var weeks = jdbc.sql("select count(*) from schedule.rotation_week")
                .query(Integer.class)
                .single();

        assertThat(duration).isEqualTo(120);
        assertThat(anchor).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(weeks).isZero();
    }

    @Test
    void exclusionConstraintOnSlotsIsInPlace() {
        var extension = jdbc.sql("select count(*) from pg_extension where extname = 'btree_gist'")
                .query(Integer.class)
                .single();
        var constraint = jdbc.sql("select count(*) from pg_constraint where conname = 'slot_no_overlap'")
                .query(Integer.class)
                .single();

        assertThat(extension).isEqualTo(1);
        assertThat(constraint).isEqualTo(1);
    }
}
