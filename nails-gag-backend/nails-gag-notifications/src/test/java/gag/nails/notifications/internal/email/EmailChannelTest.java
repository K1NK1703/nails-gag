package gag.nails.notifications.internal.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.Managers;
import com.icegreen.greenmail.mail.MailAddress;
import com.icegreen.greenmail.smtp.SmtpManager;
import com.icegreen.greenmail.smtp.SmtpState;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.icegreen.greenmail.util.ServerSetupTest;
import gag.nails.notifications.internal.MailServer;
import gag.nails.notifications.internal.dispatch.RenderedText;
import gag.nails.notifications.internal.dispatch.ResolvedRecipient;
import gag.nails.notifications.internal.dispatch.SendResult;
import gag.nails.notifications.model.Channel;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Канал почты на почтовом сервере в памяти (GreenMail): письмо — простой текст в UTF-8 с темой, телом и подвалом;
 * отказы и сбои сервера — кодами результата, без исключений.
 */
class EmailChannelTest {

    private static final String FROM = "nails.gag <noreply@nails-gag.ru>";
    private static final String SITE_URL = "https://nails-gag.ru";
    private static final RenderedText TEXT = RenderedText.of("""
            Вы записаны на пн, 5 октября, 14:00
            Анна, мастер записал вас на визит.
            Адрес: пр. КИМа 6, метро Приморская, 3 этаж, студия 365""");

    /** Таймауты SMTP тестов: в пределах секунд, чтобы молчащий сервер не держал тест. */
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private @Nullable GreenMail mailServer;

    @AfterEach
    void stopMailServer() {
        if (mailServer != null) {
            mailServer.stop();
        }
    }

    @Test
    void letterIsPlainUtf8TextWithSubjectBodyAndFooter() throws Exception {
        var server = start(new GreenMail(ServerSetupTest.SMTP.dynamicPort()));
        var channel = channel(sender(server.getSmtp().getPort()));

        var result = channel.send(new ResolvedRecipient("anna@example.com", null, "Анна"), TEXT);

        assertThat(result).isEqualTo(new SendResult.Sent());
        assertThat(server.waitForIncomingEmail(5_000, 1)).isTrue();
        var letter = single(server);
        var from = (InternetAddress) letter.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("noreply@nails-gag.ru");
        assertThat(from.getPersonal()).isEqualTo("nails.gag");
        var to = (InternetAddress) letter.getRecipients(RecipientType.TO)[0];
        assertThat(letter.getRecipients(RecipientType.TO)).hasSize(1);
        assertThat(to.getAddress()).isEqualTo("anna@example.com");
        assertThat(to.getPersonal()).isEqualTo("Анна");
        assertThat(letter.getHeader("To")[0]).startsWithIgnoringCase("=?UTF-8?");
        assertThat(letter.getSubject()).isEqualTo("Вы записаны на пн, 5 октября, 14:00");
        assertThat(letter.getHeader("Subject")[0]).startsWithIgnoringCase("=?UTF-8?");
        var type = new ContentType(letter.getContentType());
        assertThat(type.getBaseType()).isEqualToIgnoringCase("text/plain");
        assertThat(type.getParameter("charset")).isEqualToIgnoringCase("UTF-8");
        assertThat(body(letter)).isEqualTo(TEXT.body() + "\n\nНастроить уведомления: https://nails-gag.ru/account");
    }

    @Test
    void recipientWithoutNameGetsBareAddress() throws Exception {
        var server = start(new GreenMail(ServerSetupTest.SMTP.dynamicPort()));
        var channel = channel(sender(server.getSmtp().getPort()));

        var result = channel.send(new ResolvedRecipient("anna@example.com", null, null), TEXT);

        assertThat(result).isEqualTo(new SendResult.Sent());
        assertThat(server.waitForIncomingEmail(5_000, 1)).isTrue();
        assertThat(single(server).getHeader("To")).containsExactly("anna@example.com");
    }

    @Test
    void permanentRefusalIsRejection() {
        var server = start(new RefusingGreenMail(Map.of("gone@example.com", "550 5.1.1 Mailbox unavailable")));
        var channel = channel(sender(server.getSmtp().getPort()));

        var result = channel.send(new ResolvedRecipient("gone@example.com", null, "Анна"), TEXT);

        assertThat(result).isEqualTo(new SendResult.Fail("email_rejected", false));
        assertThat(server.getReceivedMessages()).isEmpty();
    }

    @Test
    void temporaryRefusalIsRetried() {
        var server = start(new RefusingGreenMail(Map.of("full@example.com", "452 4.2.2 Mailbox full")));
        var channel = channel(sender(server.getSmtp().getPort()));

        var result = channel.send(new ResolvedRecipient("full@example.com", null, "Анна"), TEXT);

        assertThat(result).isEqualTo(new SendResult.Retry(null, "email_deferred"));
        assertThat(server.getReceivedMessages()).isEmpty();
    }

    @Test
    void unreachableServerIsRetried() throws IOException {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var channel = channel(sender(port));

        var result = channel.send(new ResolvedRecipient("anna@example.com", null, "Анна"), TEXT);

        assertThat(result).isEqualTo(new SendResult.Retry(null, "email_unreachable"));
    }

    /**
     * Сервер принимает соединение и молчит: ожидание кончается по таймауту чтения из настроек отправителя, а не
     * никогда, — так отправка укладывается в аренду доставки.
     */
    @Test
    void silentServerTimesOutAndIsRetried() throws IOException {
        try (var silent = new ServerSocket(0)) {
            var channel = channel(sender(silent.getLocalPort()));
            var started = System.nanoTime();

            var result = channel.send(new ResolvedRecipient("anna@example.com", null, "Анна"), TEXT);

            assertThat(result).isEqualTo(new SendResult.Retry(null, "email_unreachable"));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(TIMEOUT.multipliedBy(3));
        }
    }

    @Test
    void rejectedLoginIsRetried() {
        var server = start(new GreenMail(ServerSetupTest.SMTP.dynamicPort()));
        server.setUser("noreply@nails-gag.ru", "sender", "secret");
        var sender = sender(server.getSmtp().getPort());
        sender.setUsername("sender");
        sender.setPassword("wrong");
        sender.getJavaMailProperties().setProperty("mail.smtp.auth", "true");
        var channel = channel(sender);

        var result = channel.send(new ResolvedRecipient("anna@example.com", null, "Анна"), TEXT);

        assertThat(result).isEqualTo(new SendResult.Retry(null, "email_auth_failed"));
        assertThat(server.getReceivedMessages()).isEmpty();
    }

    @Test
    void malformedAddressIsPermanentFailure() {
        var server = start(new GreenMail(ServerSetupTest.SMTP.dynamicPort()));
        var channel = channel(sender(server.getSmtp().getPort()));

        var result = channel.send(new ResolvedRecipient("anna at example", null, "Анна"), TEXT);

        assertThat(result).isEqualTo(new SendResult.Fail("email_invalid_address", false));
        assertThat(server.getReceivedMessages()).isEmpty();
    }

    @Test
    void channelIsConfiguredOnlyWithServerAndSender() throws Exception {
        var configured = new EmailChannel(new MailServer(sender(25), new InternetAddress(FROM, true)), SITE_URL);
        var withoutServer = new EmailChannel(MailServer.NONE, SITE_URL);
        var withoutSender = new EmailChannel(new MailServer(sender(25), null), SITE_URL);

        assertThat(configured.channel()).isEqualTo(Channel.EMAIL);
        assertThat(configured.configured()).isTrue();
        assertThat(withoutServer.configured()).isFalse();
        assertThat(withoutSender.configured()).isFalse();
    }

    private <T extends GreenMail> T start(T server) {
        server.start();
        mailServer = server;
        return server;
    }

    private static EmailChannel channel(JavaMailSenderImpl sender) {
        try {
            return new EmailChannel(new MailServer(sender, new InternetAddress(FROM, true)), SITE_URL);
        } catch (jakarta.mail.internet.AddressException e) {
            throw new AssertionError(e);
        }
    }

    /** Отправитель на локальный порт с короткими таймаутами SMTP. */
    private static JavaMailSenderImpl sender(int port) {
        var sender = new JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(port);
        var properties = new Properties();
        var timeout = Long.toString(TIMEOUT.toMillis());
        properties.setProperty("mail.smtp.connectiontimeout", timeout);
        properties.setProperty("mail.smtp.timeout", timeout);
        properties.setProperty("mail.smtp.writetimeout", timeout);
        sender.setJavaMailProperties(properties);
        return sender;
    }

    private static MimeMessage single(GreenMail server) {
        var letters = server.getReceivedMessages();
        assertThat(letters).hasSize(1);
        return letters[0];
    }

    /** Тело письма без различий в переводах строк: при передаче они становятся CRLF. */
    private static String body(MimeMessage letter) throws Exception {
        return ((String) letter.getContent()).replace("\r\n", "\n").stripTrailing();
    }

    /** GreenMail, который на {@code RCPT TO} заданных адресов отвечает заданной строкой (кодом отказа). */
    private static final class RefusingGreenMail extends GreenMail {

        RefusingGreenMail(Map<String, String> replies) {
            super(new ServerSetup[] {ServerSetupTest.SMTP.dynamicPort()});
            managers = new Managers() {

                private final SmtpManager smtp = new SmtpManager(getImapHostManager(), getUserManager()) {

                    @Override
                    public String checkRecipient(SmtpState state, MailAddress recipient) {
                        return replies.get(recipient.getEmail());
                    }
                };

                @Override
                public SmtpManager getSmtpManager() {
                    return smtp;
                }
            };
        }
    }
}
