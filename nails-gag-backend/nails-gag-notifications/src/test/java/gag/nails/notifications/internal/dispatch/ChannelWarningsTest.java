package gag.nails.notifications.internal.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.notifications.model.Channel;
import gag.nails.testsupport.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Сбой канала — WARN не чаще раза в минуту; пропущенные называются числом в следующей строке или при сбросе в конце
 * прогона; заблокированный бот — не сбой канала.
 */
@ExtendWith(OutputCaptureExtension.class)
class ChannelWarningsTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T06:00:00Z"), ZoneOffset.UTC);
    private final ChannelWarnings warnings = new ChannelWarnings(clock);

    @Test
    void suppressedFailuresAreCountedAndFlushed(CapturedOutput output) {
        warnings.failed(Channel.EMAIL, UUID.randomUUID(), UUID.randomUUID(), "CONFIRMED", "smtp_down");
        warnings.failed(Channel.EMAIL, UUID.randomUUID(), UUID.randomUUID(), "CONFIRMED", "smtp_down");
        warnings.failed(Channel.EMAIL, UUID.randomUUID(), UUID.randomUUID(), "CONFIRMED", "smtp_down");
        warnings.flush();

        assertThat(output.getOut()).containsOnlyOnce("notification channel EMAIL failed");

        clock.advance(Duration.ofMinutes(1));
        warnings.flush();

        assertThat(output.getOut())
                .contains("notification channel EMAIL failed 2 more times since the previous warning");
    }

    @Test
    void blockedBotIsNotAChannelFailure(CapturedOutput output) {
        warnings.failed(Channel.TELEGRAM, UUID.randomUUID(), UUID.randomUUID(), "CONFIRMED", "telegram_blocked");
        warnings.flush();

        assertThat(output.getOut())
                .doesNotContain("notification channel TELEGRAM failed")
                .contains("telegram_blocked");
    }
}
