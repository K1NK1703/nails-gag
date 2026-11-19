package gag.nails.notifications.internal.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import gag.nails.notifications.internal.NotificationsProperties;
import gag.nails.notifications.internal.telegram.TelegramStub.Reply;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Бот в контексте: канал настроен только с токеном, приём обновлений запускается только с токеном и включёнными
 * задачами и останавливается вместе с контекстом, не дожидаясь конца долгого запроса.
 */
class TelegramConfigurationTest {

    private static final String PREFIX = "nails-gag.notifications.";

    private final TelegramStub stub = TelegramStub.started();

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(Telegram.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(TelegramUpdates.class, () -> mock(TelegramUpdates.class))
            .withPropertyValues(
                    PREFIX + "site-url=https://nails-gag.ru",
                    PREFIX + "master-telegram=@gagushichewa",
                    PREFIX + "salon-address=пр. КИМа 6",
                    PREFIX + "telegram.api-url=" + stub.url());

    @AfterEach
    void stopStub() {
        stub.close();
    }

    @Test
    void withoutTokenNothingIsPolledAndChannelIsOff() {
        context.run(started -> {
            assertThat(started.getBean(TelegramChannel.class).configured()).isFalse();
            assertThat(started.getBean(TelegramApi.class).configured()).isFalse();
            assertThat(started.getBean(TelegramPoller.class).isRunning()).isFalse();
        });
        assertThat(stub.requests("getUpdates")).isEmpty();
    }

    @Test
    void withTokenThePollerRunsAndStopsWithTheContext() {
        stub.respond("getUpdates", Reply.hang(Duration.ofSeconds(30)));
        var stopped = new long[1];

        context.withPropertyValues(PREFIX + "telegram.bot-token=55:token", PREFIX + "telegram.bot-username=nails_bot")
                .run(started -> {
                    assertThat(started.getBean(TelegramChannel.class).configured())
                            .isTrue();
                    assertThat(started.getBean(TelegramPoller.class).isRunning())
                            .isTrue();
                    assertThat(stub.await("getUpdates", 1).getFirst().token()).isEqualTo("55:token");
                    stopped[0] = System.nanoTime();
                });

        assertThat(Duration.ofNanos(System.nanoTime() - stopped[0])).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void jobsOffMeanNoPoller() {
        context.withPropertyValues(
                        PREFIX + "telegram.bot-token=55:token",
                        PREFIX + "telegram.bot-username=nails_bot",
                        PREFIX + "jobs.enabled=false")
                .run(started -> {
                    assertThat(started.getBean(TelegramChannel.class).configured())
                            .isTrue();
                    assertThat(started).doesNotHaveBean(TelegramPoller.class);
                });
    }

    /** Настройки модуля и бот — без остального модуля. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotificationsProperties.class)
    @Import(TelegramConfiguration.class)
    static class Telegram {}
}
