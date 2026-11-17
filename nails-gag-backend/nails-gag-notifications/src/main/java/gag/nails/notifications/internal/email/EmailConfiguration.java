package gag.nails.notifications.internal.email;

import gag.nails.common.error.InternalException;
import gag.nails.notifications.internal.MailServer;
import gag.nails.notifications.internal.NotificationsProperties;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Почта уведомлений: {@link MailServer} из настроек Spring Boot ({@code spring.mail.*}) и модуля и канал
 * {@link EmailChannel}.
 *
 * <p>Таймауты SMTP у настроенной почты обязательны ({@code spring.mail.properties[mail.<протокол>.connectiontimeout]},
 * {@code …timeout}, {@code …writetimeout}, в миллисекундах): отправка держит доставку в аренде 5 минут от начала своей
 * попытки, а без таймаутов JavaMail ждёт сервер бесконечно — зависший сервер остановил бы весь прогон отправки.
 * Письмо — около десятка обменов с сервером (приветствие, EHLO, STARTTLS и снова EHLO, вход, MAIL, RCPT, DATA, конец
 * письма, QUIT), поэтому каждый таймаут — не больше {@link #MAX_TIMEOUT}: так даже сервер, отвечающий на каждую
 * команду на грани таймаута, укладывается в аренду. Нет таймаута или он больше — приложение не стартует.
 */
@Configuration(proxyBeanMethods = false)
class EmailConfiguration {

    /** Предел каждого таймаута SMTP. */
    static final Duration MAX_TIMEOUT = Duration.ofSeconds(20);

    private static final List<String> TIMEOUTS = List.of("connectiontimeout", "timeout", "writetimeout");

    @Bean
    MailServer mailServer(
            ObjectProvider<JavaMailSender> sender, Environment environment, NotificationsProperties properties) {
        return mailServer(
                environment.getProperty("spring.mail.host"),
                sender.getIfAvailable(),
                properties.email().from());
    }

    @Bean
    EmailChannel emailChannel(MailServer mailServer, NotificationsProperties properties) {
        return new EmailChannel(mailServer, properties.siteUrl());
    }

    /**
     * Почтовый сервер по настройкам: без сервера, без созданного Spring Boot отправителя или без адреса отправителя —
     * {@link MailServer#NONE}; у настроенной почты проверяются таймауты SMTP.
     *
     * @param host {@code spring.mail.host}
     * @param from {@code nails-gag.notifications.email.from}, уже проверенный как адрес при старте
     */
    static MailServer mailServer(@Nullable String host, @Nullable JavaMailSender sender, String from) {
        if (host == null || host.isBlank() || sender == null || from.isBlank()) {
            return MailServer.NONE;
        }
        if (sender instanceof JavaMailSenderImpl impl) {
            requireTimeouts(impl);
        }
        try {
            return new MailServer(sender, new InternetAddress(from, true));
        } catch (AddressException e) {
            throw new InternalException("nails-gag.notifications.email.from is not an email address", e);
        }
    }

    /**
     * Таймауты протокола отправителя в сессии JavaMail — той, которой он отправляет, — заданы целым числом
     * миллисекунд от 1 до {@link #MAX_TIMEOUT}; значение, которое JavaMail не разберёт как целое, она молча заменила бы
     * бесконечным ожиданием. Отправитель другой реализации, чем у Spring Boot, отвечает за свои таймауты сам.
     */
    private static void requireTimeouts(JavaMailSenderImpl sender) {
        var protocol = sender.getProtocol() == null ? "smtp" : sender.getProtocol();
        var session = sender.getSession();
        for (var timeout : TIMEOUTS) {
            var key = "mail." + protocol + "." + timeout;
            var value = session.getProperty(key);
            if (!withinLimit(value)) {
                throw new InternalException("spring.mail.properties[" + key + "] must be set to 1.."
                        + MAX_TIMEOUT.toMillis() + " milliseconds, was " + value);
            }
        }
    }

    private static boolean withinLimit(@Nullable String millis) {
        if (millis == null) {
            return false;
        }
        try {
            var value = Integer.parseInt(millis);
            return value > 0 && value <= MAX_TIMEOUT.toMillis();
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
