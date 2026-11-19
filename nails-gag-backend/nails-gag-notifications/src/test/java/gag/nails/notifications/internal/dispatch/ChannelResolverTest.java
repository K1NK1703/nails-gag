package gag.nails.notifications.internal.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import gag.nails.common.error.InternalException;
import gag.nails.identity.StaffDirectory;
import gag.nails.notifications.internal.preference.AccountChannels;
import gag.nails.notifications.model.Channel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Выбор реализации канала: ненастроенная реализация не мешает настроенной (подменная рядом с настоящей), а две
 * настроенные реализации одного канала — ошибка конфигурации, видная уже при старте, а не при первой отправке.
 */
class ChannelResolverTest {

    @Test
    void configuredImplementationWinsOverUnconfiguredOne() {
        var unconfigured = new Stub(Channel.EMAIL, false);
        var configured = new Stub(Channel.EMAIL, true);
        var resolver = resolver(unconfigured, configured);

        assertThatCode(resolver::afterSingletonsInstantiated).doesNotThrowAnyException();
        assertThat(resolver.channel(Channel.EMAIL)).containsSame(configured);
        assertThat(resolver.channel(Channel.TELEGRAM)).isEmpty();
    }

    @Test
    void unconfiguredChannelIsUnavailable() {
        var resolver = resolver(new Stub(Channel.EMAIL, false), new Stub(Channel.EMAIL, false));

        assertThatCode(resolver::afterSingletonsInstantiated).doesNotThrowAnyException();
        assertThat(resolver.channel(Channel.EMAIL)).isEmpty();
    }

    @Test
    void twoConfiguredImplementationsFailAtStartup() {
        var resolver = resolver(
                new Stub(Channel.EMAIL, true), new Stub(Channel.TELEGRAM, true), new Stub(Channel.EMAIL, true));

        assertThatThrownBy(resolver::afterSingletonsInstantiated)
                .isInstanceOf(InternalException.class)
                .hasMessageContaining("EMAIL");
        assertThatThrownBy(() -> resolver.channel(Channel.EMAIL)).isInstanceOf(InternalException.class);
        assertThat(resolver.channel(Channel.TELEGRAM)).isPresent();
    }

    private static ChannelResolver resolver(NotificationChannel... channels) {
        return new ChannelResolver(mock(StaffDirectory.class), mock(AccountChannels.class), List.of(channels));
    }

    /** Реализация канала, которая только сообщает, настроена ли она; отправлять ей нечего. */
    private record Stub(Channel channel, boolean configured) implements NotificationChannel {

        @Override
        public SendResult send(ResolvedRecipient recipient, RenderedText text) {
            throw new AssertionError("not expected to send");
        }
    }
}
