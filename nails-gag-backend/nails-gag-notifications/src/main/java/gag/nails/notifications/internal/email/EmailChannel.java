package gag.nails.notifications.internal.email;

import gag.nails.common.error.InternalException;
import gag.nails.notifications.internal.MailServer;
import gag.nails.notifications.internal.dispatch.NotificationChannel;
import gag.nails.notifications.internal.dispatch.RenderedText;
import gag.nails.notifications.internal.dispatch.ResolvedRecipient;
import gag.nails.notifications.internal.dispatch.SendResult;
import gag.nails.notifications.model.Channel;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Письма уведомлений (спека §3.7): простой текст {@code text/plain; charset=UTF-8} от отправителя из настроек, тема —
 * первая строка текста, в конце — ссылка на настройки уведомлений в личном кабинете. Отправляет
 * {@code JavaMailSender} Spring Boot ({@code spring.mail.*}): каждое письмо — своё соединение, таймауты SMTP заданы и
 * проверены при старте ({@link EmailConfiguration}), поэтому попытка укладывается в аренду доставки.
 *
 * <p>Исход — кодом результата, а не исключением; текст исключений JavaMail (в нём бывают адреса и ответы сервера с
 * ними) никуда не передаётся, в отладочный лог — только классы и код ответа SMTP:
 *
 * <ul>
 *   <li>{@value #REJECTED} — сервер ответил на письмо постоянным отказом 5xx (получатель, отправитель или содержимое):
 *       доставка неудачна сразу;
 *   <li>{@value #INVALID_ADDRESS} — почта получателя не разбирается как адрес: доставка неудачна сразу;
 *   <li>{@value #DEFERRED} — временный отказ сервера 4xx: повтор по расписанию;
 *   <li>{@value #UNREACHABLE} — нет соединения или ответа сервера, в том числе по таймауту: повтор;
 *   <li>{@value #AUTH_FAILED} — сервер не принял вход: повтор — настройки можно исправить, пока попытки не кончились;
 *   <li>{@value #FAILED} — прочий сбой почты без кода ответа SMTP: повтор.
 * </ul>
 */
final class EmailChannel implements NotificationChannel {

    static final String REJECTED = "email_rejected";
    static final String INVALID_ADDRESS = "email_invalid_address";
    static final String DEFERRED = "email_deferred";
    static final String UNREACHABLE = "email_unreachable";
    static final String AUTH_FAILED = "email_auth_failed";
    static final String FAILED = "email_error";

    private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);

    private final MailServer server;
    private final String footer;

    /**
     * @param siteUrl адрес сайта без завершающего слэша — для ссылки на настройки уведомлений
     */
    EmailChannel(MailServer server, String siteUrl) {
        this.server = server;
        this.footer = "Настроить уведомления: " + siteUrl + "/account";
    }

    @Override
    public Channel channel() {
        return Channel.EMAIL;
    }

    @Override
    public boolean configured() {
        return server.configured();
    }

    @Override
    public SendResult send(ResolvedRecipient recipient, RenderedText text) {
        var sender = server.sender();
        var from = server.from();
        var email = recipient.email();
        if (sender == null || from == null || email == null) {
            throw new InternalException("email is sent only by a configured channel to a recipient with an address");
        }
        InternetAddress to;
        try {
            to = address(email, recipient.name());
        } catch (AddressException e) {
            return new SendResult.Fail(INVALID_ADDRESS, false);
        }
        MimeMessage letter;
        try {
            letter = letter(sender.createMimeMessage(), from, to, text);
        } catch (MessagingException e) {
            log.debug("email letter not composed: {}", e.getClass().getName());
            return new SendResult.Retry(null, FAILED);
        }
        try {
            sender.send(letter);
            return new SendResult.Sent();
        } catch (MailException e) {
            return failure(e);
        }
    }

    private MimeMessage letter(MimeMessage letter, InternetAddress from, InternetAddress to, RenderedText text)
            throws MessagingException {
        var helper = new MimeMessageHelper(letter, false, StandardCharsets.UTF_8.name());
        helper.setFrom(from);
        helper.setTo(to);
        helper.setSubject(text.subject());
        helper.setText(text.body() + "\n\n" + footer, false);
        return letter;
    }

    /** Адрес получателя с его именем в заголовке {@code To}, если оно есть. */
    private static InternetAddress address(String email, @Nullable String name) throws AddressException {
        var address = new InternetAddress(email, true);
        if (name != null && !name.isBlank()) {
            try {
                address.setPersonal(name, StandardCharsets.UTF_8.name());
            } catch (UnsupportedEncodingException e) {
                throw new InternalException("UTF-8 is not supported", e);
            }
        }
        return address;
    }

    /** Исход по сбою отправки: код ответа SMTP, если сервер его дал, иначе — по виду сбоя. */
    private static SendResult failure(MailException error) {
        if (error instanceof MailAuthenticationException) {
            log.debug("email login rejected: {}", error.getClass().getName());
            return new SendResult.Retry(null, AUTH_FAILED);
        }
        var chain = chain(error);
        var reply = chain.stream()
                .mapToInt(EmailChannel::replyCode)
                .filter(code -> code >= 400 && code < 600)
                .findFirst()
                .orElse(-1);
        log.debug("email not sent: {}, SMTP reply {}", error.getClass().getName(), reply);
        if (reply >= 500) {
            return new SendResult.Fail(REJECTED, false);
        }
        if (reply >= 400) {
            return new SendResult.Retry(null, DEFERRED);
        }
        var unreachable = chain.stream().anyMatch(IOException.class::isInstance);
        return new SendResult.Retry(null, unreachable ? UNREACHABLE : FAILED);
    }

    /** Код ответа SMTP из исключения Angus Mail; {@code -1} — у исключения его нет. */
    private static int replyCode(Throwable error) {
        return switch (error) {
            case SMTPAddressFailedException e -> e.getReturnCode();
            case SMTPSenderFailedException e -> e.getReturnCode();
            case SMTPSendFailedException e -> e.getReturnCode();
            default -> -1;
        };
    }

    /**
     * Сбой и всё, что к нему привело, по ширине: причины, следующие исключения JavaMail и исключения отдельных писем
     * {@link MailSendException}, каждое один раз.
     */
    private static List<Throwable> chain(Throwable error) {
        var chain = new ArrayList<Throwable>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var queue = new ArrayDeque<Throwable>();
        queue.add(error);
        while (!queue.isEmpty()) {
            var current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            chain.add(current);
            if (current.getCause() != null) {
                queue.add(current.getCause());
            }
            if (current instanceof MessagingException messaging && messaging.getNextException() != null) {
                queue.add(messaging.getNextException());
            }
            if (current instanceof MailSendException send) {
                queue.addAll(List.of(send.getMessageExceptions()));
            }
        }
        return chain;
    }
}
