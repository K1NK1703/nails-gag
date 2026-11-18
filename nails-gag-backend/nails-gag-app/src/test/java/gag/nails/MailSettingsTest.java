package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Настройки SMTP из {@code application.yaml} доходят до сессии JavaMail, которой отправляются письма уведомлений:
 * таймауты (отправка держит доставку в аренде 5 минут, а без них зависший почтовый сервер держал бы её бесконечно),
 * обязательный STARTTLS, имя в {@code EHLO} и закрытие без ожидания ответа на {@code QUIT}. Профиль {@code local}
 * шлёт в Mailpit без TLS. Почтовый сервер задаётся при развёртывании, поэтому здесь — свой адрес поверх настроек.
 */
class MailSettingsTest {

    private static final Map<String, String> SHARED = Map.of(
            "mail.smtp.connectiontimeout", "10000",
            "mail.smtp.timeout", "20000",
            "mail.smtp.writetimeout", "20000",
            "mail.smtp.quitwait", "false",
            "mail.smtp.localhost", "nails-gag.ru");

    @Test
    void smtpSettingsFromApplicationYamlReachMailSession() {
        context("application.yaml")
                .withPropertyValues("spring.mail.host=smtp.example.com")
                .run(started -> assertThat(started.getBean(JavaMailSenderImpl.class)
                                .getSession()
                                .getProperties())
                        .containsAllEntriesOf(SHARED)
                        .containsEntry("mail.smtp.starttls.enable", "true")
                        .containsEntry("mail.smtp.starttls.required", "true"));
    }

    @Test
    void localProfileSendsToMailpitWithoutTls() {
        context("application.yaml", "application-local.yaml").run(started -> {
            var sender = started.getBean(JavaMailSenderImpl.class);
            assertThat(sender.getHost()).isEqualTo("localhost");
            assertThat(sender.getPort()).isEqualTo(1025);
            assertThat(sender.getSession().getProperties())
                    .containsAllEntriesOf(SHARED)
                    .containsEntry("mail.smtp.starttls.enable", "false")
                    .containsEntry("mail.smtp.starttls.required", "false");
        });
    }

    /** Файлы настроек в порядке старшинства: последующий сильнее (как профиль поверх основного файла). */
    private static ApplicationContextRunner context(String... files) {
        return new ApplicationContextRunner()
                .withInitializer(context -> load(context, files))
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class));
    }

    private static void load(ConfigurableApplicationContext context, String... files) {
        var sources = context.getEnvironment().getPropertySources();
        for (var file : files) {
            try {
                new YamlPropertySourceLoader()
                        .load(file, new ClassPathResource(file))
                        .forEach(sources::addFirst);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
