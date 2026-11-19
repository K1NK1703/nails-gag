package gag.nails.notifications;

import gag.nails.notifications.internal.dispatch.NotificationChannel;
import gag.nails.notifications.internal.dispatch.RenderedText;
import gag.nails.notifications.internal.dispatch.ResolvedRecipient;
import gag.nails.notifications.internal.dispatch.SendResult;
import gag.nails.notifications.model.Channel;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

/**
 * Подменный канал для тестов отправки: настроен или нет, отвечает заданным результатом (по умолчанию — доставлено) и
 * запоминает каждую попытку. Тесты отбирают попытки по адресам своих получателей: прогон отправки берёт и чужие
 * созревшие доставки.
 */
final class FakeChannel implements NotificationChannel {

    private final Channel channel;
    private final List<Attempt> attempts = new CopyOnWriteArrayList<>();
    private volatile boolean configured = true;
    private volatile Duration delay = Duration.ZERO;
    private volatile BiFunction<ResolvedRecipient, RenderedText, SendResult> responder = FakeChannel::sent;

    FakeChannel(Channel channel) {
        this.channel = channel;
    }

    @Override
    public Channel channel() {
        return channel;
    }

    @Override
    public boolean configured() {
        return configured;
    }

    @Override
    public SendResult send(ResolvedRecipient recipient, RenderedText text) {
        if (!delay.isZero()) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        var result = responder.apply(recipient, text);
        attempts.add(new Attempt(recipient, text, result));
        return result;
    }

    void configured(boolean configured) {
        this.configured = configured;
    }

    void delay(Duration delay) {
        this.delay = delay;
    }

    /** Как отвечать; чужим получателям обычно — {@link #sent}. */
    void respond(BiFunction<ResolvedRecipient, RenderedText, SendResult> responder) {
        this.responder = responder;
    }

    /** Попытки отправить на почту {@code email}. */
    List<Attempt> toEmail(String email) {
        return attempts.stream()
                .filter(attempt -> email.equals(attempt.recipient().email()))
                .toList();
    }

    /** Попытки отправить в чат {@code chatId}. */
    List<Attempt> toChat(long chatId) {
        return attempts.stream()
                .filter(attempt -> Objects.equals(chatId, attempt.recipient().chatId()))
                .toList();
    }

    void reset() {
        attempts.clear();
        configured = true;
        delay = Duration.ZERO;
        responder = FakeChannel::sent;
    }

    static SendResult sent(ResolvedRecipient recipient, RenderedText text) {
        return new SendResult.Sent();
    }

    /** Попытка отправки: кому, что и с каким ответом. */
    record Attempt(ResolvedRecipient recipient, RenderedText text, SendResult result) {}
}
