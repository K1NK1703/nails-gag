package gag.nails.notifications.internal.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.error.InternalException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Контракт каналов: тема — первая строка текста, коды ошибок — в форме колонки журнала, адреса не в логах. */
class SendContractTest {

    @Test
    void subjectIsTheFirstLine() {
        var text = RenderedText.of("Вы записаны на сб, 21 ноября, 14:00\nАнна, ждём вас!");

        assertThat(text.subject()).isEqualTo("Вы записаны на сб, 21 ноября, 14:00");
        assertThat(text.body()).isEqualTo("Вы записаны на сб, 21 ноября, 14:00\nАнна, ждём вас!");
        assertThat(RenderedText.of("Одна строка").subject()).isEqualTo("Одна строка");
    }

    @Test
    void subjectMustStartTheBody() {
        assertThatThrownBy(() -> new RenderedText("Тема", "Другой текст")).isInstanceOf(InternalException.class);
        assertThatThrownBy(() -> RenderedText.of("\nТекст")).isInstanceOf(InternalException.class);
    }

    @Test
    void errorCodesHaveTheJournalForm() {
        assertThatCode(() -> new SendResult.Fail("telegram_blocked", true)).doesNotThrowAnyException();
        assertThatCode(() -> new SendResult.Retry(Duration.ofSeconds(30), "telegram_throttled"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> new SendResult.Fail("Telegram blocked", false)).isInstanceOf(InternalException.class);
        assertThatThrownBy(() -> new SendResult.Retry(null, "")).isInstanceOf(InternalException.class);
        assertThatThrownBy(() -> new SendResult.Retry(null, "x".repeat(33))).isInstanceOf(InternalException.class);
    }

    @Test
    void recipientHidesAddressesInToString() {
        var recipient = new ResolvedRecipient("anna@example.com", 123456789L, "Анна");

        assertThat(recipient.toString())
                .doesNotContain("anna@example.com")
                .doesNotContain("123456789")
                .doesNotContain("Анна");
    }

    @Test
    void pausesGrowAndAttemptsEndAtFive() {
        assertThat(RetryPolicy.pauseAfter(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(RetryPolicy.pauseAfter(2)).isEqualTo(Duration.ofMinutes(5));
        assertThat(RetryPolicy.pauseAfter(3)).isEqualTo(Duration.ofMinutes(30));
        assertThat(RetryPolicy.pauseAfter(4)).isEqualTo(Duration.ofMinutes(120));
        assertThat(RetryPolicy.canRetry(4)).isTrue();
        assertThat(RetryPolicy.canRetry(5)).isFalse();
    }
}
