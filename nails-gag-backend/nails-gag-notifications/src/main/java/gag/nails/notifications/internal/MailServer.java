package gag.nails.notifications.internal;

import jakarta.mail.internet.InternetAddress;
import org.jspecify.annotations.Nullable;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Почтовый сервер писем уведомлений (спека §3.7, §3.9). Настроен, если заданы и сервер ({@code spring.mail.host} —
 * тогда Spring Boot создаёт {@link JavaMailSender}), и отправитель ({@code nails-gag.notifications.email.from}). Не
 * настроен — почта недоступна всем: в кабинете ({@code MyChannels.emailAvailable}) и при отправке. Бин собирает
 * настройка почты модуля ({@code internal.email}); здесь он — чтобы кабинет не зависел от пакета канала.
 *
 * @param sender кто отправляет письма; пусто — почта не настроена
 * @param from отправитель писем; пусто — почта не настроена
 */
public record MailServer(
        @Nullable JavaMailSender sender, @Nullable InternetAddress from) {

    /** Почта не настроена. */
    public static final MailServer NONE = new MailServer(null, null);

    /** Без сервера или без отправителя почты нет: оба поля пусты. */
    public MailServer {
        if (sender == null || from == null) {
            sender = null;
            from = null;
        }
    }

    /** Почта настроена: есть и сервер, и отправитель. */
    public boolean configured() {
        return sender != null;
    }
}
