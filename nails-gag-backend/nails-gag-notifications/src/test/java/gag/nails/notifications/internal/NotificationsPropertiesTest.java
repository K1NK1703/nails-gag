package gag.nails.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.notifications.internal.NotificationsProperties.QuietHoursWindow;
import java.time.Duration;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.validation.FieldError;

class NotificationsPropertiesTest {

    private static final String PREFIX = "nails-gag.notifications.";

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(NotificationsConfiguration.class)
            .withPropertyValues(
                    PREFIX + "site-url=https://nails-gag.ru",
                    PREFIX + "master-telegram=@gagushichewa",
                    PREFIX + "salon-address=пр. КИМа 6");

    @Test
    void defaultsFollowSpecification() {
        context.run(started -> {
            var properties = started.getBean(NotificationsProperties.class);
            assertThat(properties.jobs().enabled()).isTrue();
            assertThat(properties.jobs().dispatchDelay()).isEqualTo(Duration.ofMinutes(1));
            assertThat(properties.jobs().purgeCron()).isEqualTo("0 15 4 * * *");
            assertThat(properties.retentionDays()).isEqualTo(90);
            assertThat(properties.quietHours())
                    .isEqualTo(new QuietHoursWindow(LocalTime.of(22, 0), LocalTime.of(9, 0)));
            assertThat(properties.email().from()).isEmpty();
            assertThat(properties.telegram().configured()).isFalse();
            assertThat(properties.telegram().pollTimeout()).isEqualTo(Duration.ofSeconds(50));
        });
    }

    @Test
    void quietHoursBindFromClockTime() {
        context.withPropertyValues(PREFIX + "quiet-hours.from=23:30", PREFIX + "quiet-hours.to=07:15")
                .run(started -> assertThat(
                                started.getBean(NotificationsProperties.class).quietHours())
                        .isEqualTo(new QuietHoursWindow(LocalTime.of(23, 30), LocalTime.of(7, 15))));
    }

    @Test
    void siteMasterAndAddressAreRequired() {
        new ApplicationContextRunner()
                .withUserConfiguration(NotificationsConfiguration.class)
                .withPropertyValues(PREFIX + "master-telegram=@gagushichewa", PREFIX + "salon-address=студия")
                .run(started -> assertRejected(started, "siteUrl"));
        context.withPropertyValues(PREFIX + "master-telegram=gagushichewa")
                .run(started -> assertRejected(started, "masterTelegram"));
        context.withPropertyValues(PREFIX + "salon-address= ").run(started -> assertRejected(started, "salonAddress"));
    }

    @Test
    void siteUrlHasNoPath() {
        context.withPropertyValues(PREFIX + "site-url=https://nails-gag.ru/")
                .run(started -> assertRejected(started, "siteUrl"));
        context.withPropertyValues(PREFIX + "site-url=nails-gag.ru").run(started -> assertRejected(started, "siteUrl"));
        context.withPropertyValues(PREFIX + "site-url=http://localhost:5173")
                .run(started -> assertThat(started).hasNotFailed());
    }

    @Test
    void botTokenRequiresUsername() {
        context.withPropertyValues(PREFIX + "telegram.bot-token=123:secret")
                .run(started -> assertRejected(started, "telegram.usernameSetWhenConfigured"));
        context.withPropertyValues(PREFIX + "telegram.bot-token=123:secret", PREFIX + "telegram.bot-username=@nails")
                .run(started -> assertRejected(started, "telegram.botUsername"));
        context.withPropertyValues(
                        PREFIX + "telegram.bot-token=123:secret", PREFIX + "telegram.bot-username=nails_gag_bot")
                .run(started -> {
                    assertThat(started).hasNotFailed();
                    var telegram =
                            started.getBean(NotificationsProperties.class).telegram();
                    assertThat(telegram.configured()).isTrue();
                    assertThat(telegram.toString()).doesNotContain("secret").contains("<set>");
                });
    }

    @Test
    void botTokenMustBeWellFormedAndIsNotEchoed() {
        for (var token : new String[] {"secret", "123:sec ret", "123:secret/../x", "abc:secret"}) {
            context.withPropertyValues(
                            PREFIX + "telegram.bot-token=" + token, PREFIX + "telegram.bot-username=nails_gag_bot")
                    .run(started -> {
                        assertRejected(started, "telegram.botTokenWellFormed");
                        assertThat(NestedExceptionUtils.getRootCause(started.getStartupFailure())
                                        .getMessage())
                                .doesNotContain("secret")
                                .doesNotContain("sec ret");
                    });
        }
        context.withPropertyValues(
                        PREFIX + "telegram.bot-token=123456789:AAH-x_Y9",
                        PREFIX + "telegram.bot-username=nails_gag_bot")
                .run(started -> assertThat(started).hasNotFailed());
    }

    @Test
    void botApiUrlDefaultsToTelegramAndHasNoPath() {
        context.run(started -> assertThat(started.getBean(NotificationsProperties.class)
                        .telegram()
                        .apiUrl())
                .isEqualTo("https://api.telegram.org"));
        context.withPropertyValues(PREFIX + "telegram.api-url=https://api.telegram.org/bot")
                .run(started -> assertRejected(started, "telegram.apiUrl"));
        context.withPropertyValues(PREFIX + "telegram.api-url=http://127.0.0.1:8081")
                .run(started -> assertThat(started).hasNotFailed());
    }

    @Test
    void durationsAndRetentionMustBePositive() {
        context.withPropertyValues(PREFIX + "jobs.dispatch-delay=0s")
                .run(started -> assertRejected(started, "jobs.dispatchDelay"));
        context.withPropertyValues(PREFIX + "telegram.poll-timeout=0s")
                .run(started -> assertRejected(started, "telegram.pollTimeout"));
        context.withPropertyValues(PREFIX + "telegram.poll-timeout=61s")
                .run(started -> assertRejected(started, "telegram.pollTimeout"));
        context.withPropertyValues(PREFIX + "retention-days=0")
                .run(started -> assertRejected(started, "retentionDays"));
    }

    @Test
    void purgeCronMustBeValid() {
        context.withPropertyValues(PREFIX + "jobs.purge-cron=0 15 4 * *")
                .run(started -> assertRejected(started, "jobs.purgeCronValid"));
        context.withPropertyValues(PREFIX + "jobs.purge-cron=каждую ночь")
                .run(started -> assertRejected(started, "jobs.purgeCronValid"));
        context.withPropertyValues(PREFIX + "jobs.purge-cron=0 0 3 * * MON")
                .run(started -> assertThat(started).hasNotFailed());
    }

    @Test
    void emailFromMustBeAnAddress() {
        context.withPropertyValues(PREFIX + "email.from=noreply")
                .run(started -> assertRejected(started, "email.fromValid"));
        context.withPropertyValues(PREFIX + "email.from=a@b.ru, c@d.ru")
                .run(started -> assertRejected(started, "email.fromValid"));
        context.withPropertyValues(PREFIX + "email.from=nails.gag <noreply@nails-gag.ru>")
                .run(started -> assertThat(started.getBean(NotificationsProperties.class)
                                .email()
                                .from())
                        .isEqualTo("nails.gag <noreply@nails-gag.ru>"));
        context.withPropertyValues(PREFIX + "email.from=noreply@nails-gag.ru")
                .run(started -> assertThat(started).hasNotFailed());
    }

    @Test
    void quietHoursMayWrapMidnight() {
        var night = new QuietHoursWindow(LocalTime.of(22, 0), LocalTime.of(9, 0));
        var day = new QuietHoursWindow(LocalTime.of(13, 0), LocalTime.of(14, 0));
        var none = new QuietHoursWindow(LocalTime.of(22, 0), LocalTime.of(22, 0));

        assertThat(night.contains(LocalTime.of(21, 59))).isFalse();
        assertThat(night.contains(LocalTime.of(22, 0))).isTrue();
        assertThat(night.contains(LocalTime.MIDNIGHT)).isTrue();
        assertThat(night.contains(LocalTime.of(8, 59, 59))).isTrue();
        assertThat(night.contains(LocalTime.of(9, 0))).isFalse();
        assertThat(day.contains(LocalTime.of(12, 59))).isFalse();
        assertThat(day.contains(LocalTime.of(13, 0))).isTrue();
        assertThat(day.contains(LocalTime.of(14, 0))).isFalse();
        assertThat(none.contains(LocalTime.of(22, 0))).isFalse();
    }

    /** Контекст не поднялся из-за проверки ровно этого поля настроек. */
    private static void assertRejected(AssertableApplicationContext started, String field) {
        assertThat(started).hasFailed();
        assertThat(NestedExceptionUtils.getRootCause(started.getStartupFailure()))
                .as(field)
                .isInstanceOfSatisfying(
                        BindValidationException.class,
                        e -> assertThat(e.getValidationErrors().getAllErrors())
                                .extracting(error -> ((FieldError) error).getField())
                                .containsExactly(field));
    }
}
