package gag.nails.analytics.internal.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.common.error.InternalException;
import gag.nails.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Лог потерь с ограничением частоты: время двигают подставные часы. */
@ExtendWith(OutputCaptureExtension.class)
class LossLogTest {

    private static final String FIRST = "analytics event lost";
    private static final String SUMMARY = "analytics lost";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T06:00:00Z"), ZoneOffset.UTC);
    private final LossLog losses = new LossLog(clock);

    @Test
    void firstFailureWithTraceThenOneLinePerMinute(CapturedOutput output) {
        losses.failed("PAGE_VIEW event was not written", new InternalException("boom-1"));
        assertThat(output.getAll())
                .contains(FIRST + ": PAGE_VIEW event was not written")
                .contains("boom-1");

        losses.failed("PAGE_VIEW event was not written", new InternalException("boom-2"));
        losses.busy();
        clock.advance(Duration.ofSeconds(59));
        losses.failed("MAP_OPEN event was not written", new InternalException("boom-3"));
        assertThat(output.getAll())
                .doesNotContain(SUMMARY)
                .doesNotContain("boom-2")
                .doesNotContain("boom-3");

        clock.advance(Duration.ofSeconds(1));
        losses.busy();

        assertThat(output.getAll())
                .contains(SUMMARY + " 4 events since 2026-10-01T06:00:00Z: 2 failed to be written, 2 dropped as busy")
                .doesNotContain("boom-2")
                .doesNotContain("boom-3");
        assertThat(occurrences(output.getAll(), "analytics ")).isEqualTo(2);
    }

    @Test
    void firstBusyDropIsLoggedWithoutTrace(CapturedOutput output) {
        losses.busy();

        assertThat(output.getAll())
                .contains(FIRST + ": dropped, too many concurrent writes")
                .doesNotContain("Exception");
    }

    @Test
    void pendingLossesAreWrittenAfterSuccessOnceTheMinuteIsOver(CapturedOutput output) {
        losses.busy();
        losses.busy();
        losses.flush();
        assertThat(output.getAll()).doesNotContain(SUMMARY);

        clock.advance(LossLog.INTERVAL);
        losses.flush();
        assertThat(output.getAll()).contains(SUMMARY + " 1 events");

        clock.advance(LossLog.INTERVAL);
        losses.flush();
        assertThat(occurrences(output.getAll(), SUMMARY)).isOne();
    }

    @Test
    void afterAQuietMinuteTheNextFailureHasATraceAgain(CapturedOutput output) {
        losses.failed("PAGE_VIEW event was not written", new InternalException("boom-1"));
        clock.advance(Duration.ofMinutes(5));
        losses.failed("PAGE_VIEW event was not written", new InternalException("boom-2"));

        assertThat(output.getAll()).contains("boom-1").contains("boom-2").doesNotContain(SUMMARY);
    }

    @Test
    void clockGoingBackDoesNotSilenceTheLog(CapturedOutput output) {
        losses.failed("PAGE_VIEW event was not written", new InternalException("boom-1"));
        clock.advance(Duration.ofHours(-1));
        losses.failed("PAGE_VIEW event was not written", new InternalException("boom-2"));

        assertThat(output.getAll()).contains("boom-2");
    }

    private static int occurrences(String text, String part) {
        var count = 0;
        for (var at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }
}
