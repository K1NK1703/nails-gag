package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Таймауты SMTP из {@code application.yaml} доходят до сессии JavaMail, которой отправляются письма уведомлений:
 * отправка держит доставку в аренде 5 минут, а без таймаутов зависший почтовый сервер держал бы её бесконечно.
 * Почтовый сервер задаётся при развёртывании, поэтому здесь — свой адрес поверх настроек приложения.
 */
class MailTimeoutsTest {

    @Test
    void smtpTimeoutsFromApplicationYamlReachMailSession() {
        new ApplicationContextRunner()
                .withInitializer(MailTimeoutsTest::applicationYaml)
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withPropertyValues("spring.mail.host=smtp.example.com")
                .run(context -> assertThat(context.getBean(JavaMailSenderImpl.class)
                                .getSession()
                                .getProperties())
                        .containsEntry("mail.smtp.connectiontimeout", "10000")
                        .containsEntry("mail.smtp.timeout", "20000")
                        .containsEntry("mail.smtp.writetimeout", "20000"));
    }

    private static void applicationYaml(ConfigurableApplicationContext context) {
        try {
            var sources =
                    new YamlPropertySourceLoader().load("application.yaml", new ClassPathResource("application.yaml"));
            sources.forEach(context.getEnvironment().getPropertySources()::addLast);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
