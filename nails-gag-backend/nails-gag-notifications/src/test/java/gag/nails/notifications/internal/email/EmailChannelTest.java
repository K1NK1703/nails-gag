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
import jakarta.mail.MessagingException;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    /** Таймауты SMTP тестов: в пределах секунд, чтобы сбойный сервер не держал тест. */
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    /** Таймауты тестов, которые ждут именно таймаута: молчащий сервер. */
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(400);

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

    /**
     * Отказ отправителю — ошибка настройки почты, а не получателя: повтор, пока её не исправят. 550 Angus Mail
     * дополняет исключением об отправителе, 554 — нет: тогда отказ узнаётся по команде {@code MAIL FROM}.
     */
    @ParameterizedTest
    @ValueSource(strings = {"550 5.7.1 Sender not allowed", "554 5.7.1 Relay access denied"})
    void permanentRefusalOfSenderIsRetried(String reply) {
        var server = start(new RefusingGreenMail(Map.of("noreply@nails-gag.ru", reply), Map.of()));
        var channel = channel(sender(server.getSmtp().getPort()));

        var result = channel.send(new ResolvedRecipient("anna@example.com", null, "Анна"), TEXT);

        assertThat(result).isEqualTo(new SendResult.Retry(null, "email_sender_rejected"));
        assertThat(server.getReceivedMessages()).isEmpty();
    }

    @Test
    void temporaryRefusalOfSenderIsDeferred() {
        var server =
                start(new RefusingGreenMail(Map.of("noreply@nails-gag.ru", "451 4.3.0 Try again later"), Map.of()));
        var channel = channel(sender(server.getSmtp().getPort()));

        var result = channel.send(new ResolvedRecipient("anna@example.com", null, "Анна"), TEXT);

        assertThat(result).isEqualTo(new SendResult.Retry(null, "email_deferred"));
    }

    @Test
    void temporaryRefusalIsRetried() {
        var server = start(new RefusingGreenMail(Map.of("full@example.com", "452 4.2.2 Mailbox full")));
        var channel = channel(sender(server.getSmtp().getPort()));

        var result = channel.send(new ResolvedRecipient("full@example.com", null, "Анна"), TEXT);

        assertThat(result).isEqualTo(new SendResult.Retry(null, "email_deferred"));
        assertThat(server.getReceivedMessages()).isEmpty();
    }

    /**
     * Порт только что закрытого сервера: соединение отвергается сразу ({@code ConnectException}), а не ждёт таймаута.
     * Что порт за эти миллисекунды займёт чужой процесс, практически исключено.
     */
    @Test
    void unreachableServerIsRetried() throws IOException {
        int port;
        try (var closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = closed.getLocalPort();
        }
        var channel = channel(sender(port, SHORT_TIMEOUT));
        var started = System.nanoTime();

        var result = channel.send(new ResolvedRecipient("anna@example.com", null, "Анна"), TEXT);

        assertThat(result).isEqualTo(new SendResult.Retry(null, "email_unreachable"));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(SHORT_TIMEOUT);
    }

    /**
     * Сервер принимает соединение и молчит: ожидание кончается по таймауту чтения из настроек отправителя, а не
     * никогда, — так отправка укладывается в аренду доставки.
     */
    @Test
    void silentServerTimesOutAndIsRetried() throws IOException {
        try (var silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var channel = channel(sender(silent.getLocalPort(), SHORT_TIMEOUT));
            var started = System.nanoTime();

            var result = channel.send(new ResolvedRecipient("anna@example.com", null, "Анна"), TEXT);

            assertThat(result).isEqualTo(new SendResult.Retry(null, "email_unreachable"));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(SHORT_TIMEOUT.multipliedBy(5));
        }
    }

    /**
     * Сервер принял письмо (250 на конец письма) и молчит на {@code QUIT}: закрытие соединения кончается таймаутом, но
     * письмо уже у сервера — доставлено, иначе повтор отправил бы его второй раз.
     */
    @Test
    void acceptedLetterIsSentEvenIfQuitTimesOut() throws IOException {
        try (var server = new ScriptedSmtpServer("250 OK queued", false)) {
            var channel = channel(sender(server.port(), SHORT_TIMEOUT));

            var result = channel.send(new ResolvedRecipient("anna@example.com", null, "Анна"), TEXT);

            assertThat(result).isEqualTo(new SendResult.Sent());
        }
    }

    @Test
    void refusalAfterEndOfLetterIsRejection() throws IOException {
        try (var server = new ScriptedSmtpServer("554 5.7.1 Message rejected as spam", true)) {
            var channel = channel(sender(server.port(), SHORT_TIMEOUT));

            var result = channel.send(new ResolvedRecipient("anna@example.com", null, "Анна"), TEXT);

            assertThat(result).isEqualTo(new SendResult.Fail("email_rejected", false));
        }
    }

    /** 552 на {@code RCPT TO} — переполненный ящик, по RFC 5321 (§4.5.3.1.10) временная ошибка, как 452. */
    @Test
    void fullMailboxOnRecipientIsDeferred() {
        var server = start(new RefusingGreenMail(Map.of("full@example.com", "552 5.2.2 Mailbox full")));
        var channel = channel(sender(server.getSmtp().getPort()));

        var result = channel.send(new ResolvedRecipient("full@example.com", null, "Анна"), TEXT);

        assertThat(result).isEqualTo(new SendResult.Retry(null, "email_deferred"));
    }

    /** Свой Message-ID в домене отправителя, а не в имени машины, на которой работает приложение. */
    @Test
    void messageIdIsInSenderDomain() throws Exception {
        var server = start(new GreenMail(ServerSetupTest.SMTP.dynamicPort()));
        var channel = channel(sender(server.getSmtp().getPort()));

        channel.send(new ResolvedRecipient("anna@example.com", null, "Анна"), TEXT);
        channel.send(new ResolvedRecipient("anna@example.com", null, "Анна"), TEXT);

        assertThat(server.waitForIncomingEmail(5_000, 2)).isTrue();
        var ids = Arrays.stream(server.getReceivedMessages())
                .map(EmailChannelTest::messageId)
                .toList();
        assertThat(ids)
                .doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id)
                        .matches("<[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}@nails-gag\\.ru>"));
    }

    /** Письмо не собирается — так будет при каждой попытке: доставка неудачна сразу, без повторов. */
    @Test
    void letterThatCannotBeComposedIsPermanentFailure() {
        var sender = new JavaMailSenderImpl() {

            @Override
            public MimeMessage createMimeMessage() {
                return new MimeMessage(getSession()) {

                    @Override
                    public void setSubject(String subject, String charset) throws MessagingException {
                        throw new MessagingException("subject is not accepted");
                    }
                };
            }
        };
        sender.setHost("127.0.0.1");

        var result = channel(sender).send(new ResolvedRecipient("anna@example.com", null, "Анна"), TEXT);

        assertThat(result).isEqualTo(new SendResult.Fail("email_compose_failed", false));
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
        return sender(port, TIMEOUT);
    }

    private static JavaMailSenderImpl sender(int port, Duration timeouts) {
        var sender = new JavaMailSenderImpl();
        sender.setHost("127.0.0.1");
        sender.setPort(port);
        var properties = new Properties();
        var timeout = Long.toString(timeouts.toMillis());
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

    private static String messageId(MimeMessage letter) {
        try {
            return letter.getMessageID();
        } catch (MessagingException e) {
            throw new AssertionError(e);
        }
    }

    /** Тело письма без различий в переводах строк: при передаче они становятся CRLF. */
    private static String body(MimeMessage letter) throws Exception {
        return ((String) letter.getContent()).replace("\r\n", "\n").stripTrailing();
    }

    /**
     * GreenMail, который на {@code MAIL FROM} и {@code RCPT TO} заданных адресов отвечает заданной строкой (кодом
     * отказа). Опирается на внутренности GreenMail 2.1 — защищённое поле {@code managers} и открытые для
     * переопределения {@code SmtpManager.checkSender}/{@code checkRecipient}: при обновлении GreenMail проверить, что
     * отказы по-прежнему приходят (тесты отказов упадут, если нет).
     */
    private static final class RefusingGreenMail extends GreenMail {

        RefusingGreenMail(Map<String, String> recipientReplies) {
            this(Map.of(), recipientReplies);
        }

        RefusingGreenMail(Map<String, String> senderReplies, Map<String, String> recipientReplies) {
            super(new ServerSetup[] {ServerSetupTest.SMTP.dynamicPort()});
            managers = new Managers() {

                private final SmtpManager smtp = new SmtpManager(getImapHostManager(), getUserManager()) {

                    @Override
                    public String checkSender(SmtpState state, MailAddress sender) {
                        return senderReplies.get(sender.getEmail());
                    }

                    @Override
                    public String checkRecipient(SmtpState state, MailAddress recipient) {
                        return recipientReplies.get(recipient.getEmail());
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
