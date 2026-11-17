package gag.nails.notifications.internal.email;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.notifications.internal.MailServer;
import gag.nails.notifications.internal.NotificationsProperties;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Почта настроена, только если заданы сервер ({@code spring.mail.host}) и отправитель; у настроенной почты таймауты
 * SMTP обязательны и короче аренды доставки, иначе приложение не стартует.
 */
class EmailConfigurationTest {

    private static final String FROM = "nails-gag.notifications.email.from=nails.gag <noreply@nails-gag.ru>";
    private static final String[] TIMEOUTS = {
        "spring.mail.properties[mail.smtp.connectiontimeout]=10000",
        "spring.mail.properties[mail.smtp.timeout]=20000",
        "spring.mail.properties[mail.smtp.writetimeout]=20000"
    };

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withUserConfiguration(Properties.class, EmailConfiguration.class)
            .withPropertyValues(
                    "nails-gag.notifications.site-url=https://nails-gag.ru",
                    "nails-gag.notifications.master-telegram=@gagushichewa",
                    "nails-gag.notifications.salon-address=пр. КИМа 6");

    @Test
    void serverAndSenderMakeMailConfigured() {
        context.withPropertyValues("spring.mail.host=localhost", FROM)
                .withPropertyValues(TIMEOUTS)
                .run(started -> {
                    assertThat(started).hasNotFailed();
                    var server = started.getBean(MailServer.class);
                    assertThat(server.configured()).isTrue();
                    assertThat(server.sender()).isSameAs(started.getBean(JavaMailSenderImpl.class));
                    assertThat(server.from())
                            .extracting(InternetAddress::getAddress, InternetAddress::getPersonal)
                            .containsExactly("noreply@nails-gag.ru", "nails.gag");
                    assertThat(started.getBean(EmailChannel.class).configured()).isTrue();
                });
    }

    @Test
    void withoutServerMailIsOff() {
        context.withPropertyValues(FROM).run(EmailConfigurationTest::assertOff);
        context.withPropertyValues("spring.mail.host= ", FROM).run(EmailConfigurationTest::assertOff);
    }

    @Test
    void withoutSenderMailIsOff() {
        context.withPropertyValues("spring.mail.host=localhost").run(EmailConfigurationTest::assertOff);
        context.withPropertyValues("spring.mail.host=localhost", "nails-gag.notifications.email.from=")
                .run(EmailConfigurationTest::assertOff);
    }

    /** Без таймаутов JavaMail ждёт ответа сервера бесконечно: зависший сервер остановил бы отправку. */
    @Test
    void configuredMailRequiresSmtpTimeouts() {
        var configured = context.withPropertyValues("spring.mail.host=localhost", FROM);

        configured.run(started -> assertRejected(started, "mail.smtp.connectiontimeout"));
        configured
                .withPropertyValues(TIMEOUTS[0], TIMEOUTS[1])
                .run(started -> assertRejected(started, "mail.smtp.writetimeout"));
        configured
                .withPropertyValues(TIMEOUTS)
                .withPropertyValues("spring.mail.properties[mail.smtp.timeout]=60000")
                .run(started -> assertRejected(started, "mail.smtp.timeout"));
        configured
                .withPropertyValues(TIMEOUTS)
                .withPropertyValues("spring.mail.properties[mail.smtp.timeout]=0")
                .run(started -> assertRejected(started, "mail.smtp.timeout"));
        configured
                .withPropertyValues(TIMEOUTS)
                .withPropertyValues("spring.mail.properties[mail.smtp.connectiontimeout]=10s")
                .run(started -> assertRejected(started, "mail.smtp.connectiontimeout"));
    }

    /** Таймауты — того протокола, которым ходит отправитель: у SMTPS свои ключи. */
    @Test
    void timeoutsFollowTheProtocol() {
        var smtps = context.withPropertyValues("spring.mail.host=localhost", "spring.mail.protocol=smtps", FROM);

        smtps.withPropertyValues(TIMEOUTS).run(started -> assertRejected(started, "mail.smtps.connectiontimeout"));
        smtps.withPropertyValues(
                        "spring.mail.properties[mail.smtps.connectiontimeout]=10000",
                        "spring.mail.properties[mail.smtps.timeout]=20000",
                        "spring.mail.properties[mail.smtps.writetimeout]=20000")
                .run(started -> assertThat(started.getBean(MailServer.class).configured())
                        .isTrue());
    }

    /** Выключенной почте таймауты не нужны. */
    @Test
    void mailThatIsOffNeedsNoTimeouts() {
        context.withPropertyValues("spring.mail.host=localhost")
                .run(started -> assertThat(started).hasNotFailed());
    }

    private static void assertOff(AssertableApplicationContext started) {
        assertThat(started).hasNotFailed();
        assertThat(started.getBean(MailServer.class).configured()).isFalse();
        assertThat(started.getBean(MailServer.class)).isEqualTo(MailServer.NONE);
        assertThat(started.getBean(EmailChannel.class).configured()).isFalse();
    }

    private static void assertRejected(AssertableApplicationContext started, String property) {
        assertThat(started).getFailure().rootCause().hasMessageContaining("spring.mail.properties[" + property + "]");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationsProperties.class)
    static class Properties {}
}
