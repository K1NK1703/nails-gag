package gag.nails.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import gag.nails.notifications.internal.dispatch.Dispatcher;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Письмо через настоящую отправку и настоящий канал почты: запись → сообщение → доставка по почте {@code SENT} →
 * письмо на почтовом сервере GreenMail. Telegram в этом контексте не настроен. Часы — четверг 1 октября 2026, 09:00 по
 * Москве; визит — в понедельник 5 октября.
 */
@NotificationsModuleTest
class EmailDeliveryTest extends QueueTestSupport {

    /** Сервер запускается до контекста: его порт — в настройках почты контекста. */
    private static final GreenMail MAIL_SERVER = started();

    @DynamicPropertySource
    static void mailServer(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", () -> MAIL_SERVER.getSmtp().getPort());
        registry.add("nails-gag.notifications.email.from", () -> "nails.gag <noreply@nails-gag.ru>");
    }

    @AfterAll
    static void stopMailServer() {
        MAIL_SERVER.stop();
    }

    @Autowired
    Dispatcher dispatcher;

    @Test
    void bookingLetterReachesMailServer() throws Exception {
        var email = "client-" + UUID.randomUUID() + "@example.com";
        var anna = client("Анна", null, email);
        var booking = bookByMaster(anna, slot(MONDAY, "14:00"), false);

        dispatcher.run();

        assertThat(jdbc.sql("""
                        select d.channel, d.status, d.attempts from notifications.delivery d
                        join notifications.message m on m.id = d.message_id
                        where m.booking_id = ? and m.kind = 'BOOKED_BY_MASTER'
                        """)
                        .param(booking.id())
                        .query((row, number) ->
                                tuple(row.getString("channel"), row.getString("status"), row.getInt("attempts")))
                        .list())
                .containsExactly(tuple("EMAIL", "SENT", 1));
        assertThat(lettersTo(email)).singleElement().satisfies(letter -> {
            assertThat(((InternetAddress) letter.getFrom()[0]).getAddress()).isEqualTo("noreply@nails-gag.ru");
            assertThat(letter.getSubject()).isEqualTo("Вы записаны на пн, 5 октября, 14:00");
            assertThat(body(letter))
                    .startsWith("Вы записаны на пн, 5 октября, 14:00\nАнна, мастер записал вас на визит.")
                    .contains("\nУслуги: Маникюр\n")
                    .endsWith("\n\nНастроить уведомления: https://nails-gag.ru/account");
        });
    }

    private static List<MimeMessage> lettersTo(String email) {
        return Arrays.stream(MAIL_SERVER.getReceivedMessages())
                .filter(letter -> {
                    try {
                        var to = letter.getRecipients(RecipientType.TO);
                        return to != null
                                && ((InternetAddress) to[0]).getAddress().equals(email);
                    } catch (MessagingException e) {
                        throw new AssertionError(e);
                    }
                })
                .toList();
    }

    private static String body(MimeMessage letter) {
        try {
            return ((String) letter.getContent()).replace("\r\n", "\n").stripTrailing();
        } catch (IOException | MessagingException e) {
            throw new AssertionError(e);
        }
    }

    private static GreenMail started() {
        var server = new GreenMail(ServerSetupTest.SMTP.dynamicPort());
        server.start();
        return server;
    }
}
