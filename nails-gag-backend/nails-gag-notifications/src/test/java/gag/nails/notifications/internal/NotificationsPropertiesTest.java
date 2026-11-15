package gag.nails.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.notifications.internal.NotificationsProperties.QuietHours;
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
            assertThat(properties.quietHours()).isEqualTo(new QuietHours(LocalTime.of(22, 0), LocalTime.of(9, 0)));
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
                        .isEqualTo(new QuietHours(LocalTime.of(23, 30), LocalTime.of(7, 15))));
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
    void quietHoursMayWrapMidnight() {
        var night = new QuietHours(LocalTime.of(22, 0), LocalTime.of(9, 0));
        var day = new QuietHours(LocalTime.of(13, 0), LocalTime.of(14, 0));
        var none = new QuietHours(LocalTime.of(22, 0), LocalTime.of(22, 0));

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
