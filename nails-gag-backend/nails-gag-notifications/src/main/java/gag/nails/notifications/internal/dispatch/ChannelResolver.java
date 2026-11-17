package gag.nails.notifications.internal.dispatch;

import gag.nails.booking.model.BookingView;
import gag.nails.clients.model.ClientView;
import gag.nails.common.error.InternalException;
import gag.nails.identity.StaffDirectory;
import gag.nails.identity.model.StaffRecipient;
import gag.nails.notifications.internal.preference.AccountChannels;
import gag.nails.notifications.internal.preference.ChannelDefaults;
import gag.nails.notifications.internal.preference.ChannelSwitches;
import gag.nails.notifications.model.Channel;
import gag.nails.notifications.model.MessageKind;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Получатель сообщения и его каналы на текущий момент (спека §3.2).
 *
 * <ul>
 *   <li>Клиентке — текущая карточка записи ({@link BookingView#clientId()}), а не карточка, на которую сообщение
 *       поставлено: напоминание, рассчитанное одновременно с объединением карточек, иначе осталось бы у удалённой.
 *       Обезличенной или исчезнувшей карточке писать некуда. Почта — почта карточки, Telegram — привязка аккаунта
 *       карточки. Умолчания каналов — как в кабинете ({@link ChannelDefaults#forAccount}): у аккаунта сотрудника —
 *       сотрудника, иначе клиентские.
 *   <li>Сотруднику — его аккаунт, пока он среди получателей мастерских сообщений ({@code StaffDirectory}); почта —
 *       почта карточки аккаунта, Telegram — его привязка, умолчания — по ролям.
 * </ul>
 *
 * Канал доступен, если он настроен на сервере (есть бин {@link NotificationChannel} и он {@code configured()}),
 * включён у получателя (своя настройка или умолчание) и у получателя есть адрес этого канала.
 */
@Component
class ChannelResolver {

    private final StaffDirectory staffDirectory;
    private final AccountChannels accountChannels;
    private final Map<Channel, NotificationChannel> channels;

    ChannelResolver(
            StaffDirectory staffDirectory, AccountChannels accountChannels, List<NotificationChannel> channels) {
        this.staffDirectory = staffDirectory;
        this.accountChannels = accountChannels;
        var byChannel = new EnumMap<Channel, NotificationChannel>(Channel.class);
        for (var channel : channels) {
            byChannel.merge(channel.channel(), channel, (first, second) -> {
                throw new InternalException("two implementations of channel " + first.channel());
            });
        }
        this.channels = byChannel;
    }

    /**
     * Получатели мастерских сообщений с кодами их ролей — один запрос на прогон: от этого списка зависят и мастерские
     * сообщения, и умолчания каналов аккаунтов сотрудников.
     */
    Map<UUID, Set<String>> staff() {
        return staffDirectory.recipients(ChannelDefaults.STAFF_SECTION, ChannelDefaults.STAFF_LEVEL).stream()
                .collect(Collectors.toMap(StaffRecipient::accountId, StaffRecipient::roleCodes));
    }

    /**
     * Получатель сообщения сейчас; пусто — писать некому: карточка обезличена или исчезла, аккаунт больше не получает
     * мастерских сообщений.
     *
     * @param accountId аккаунт мастерского сообщения ({@code message.account_id})
     * @param booking запись сообщения: её текущая карточка — получатель клиентского сообщения
     * @param staff получатели мастерских сообщений ({@link #staff()})
     * @param cards карточки, уже прочитанные в этой работе
     */
    Optional<Target> target(
            MessageKind kind,
            @Nullable UUID accountId,
            BookingView booking,
            Map<UUID, Set<String>> staff,
            Cards cards) {
        if (kind.isStaff()) {
            if (accountId == null || !staff.containsKey(accountId)) {
                return Optional.empty();
            }
            var card = cards.byAccountId(accountId).orElse(null);
            return Optional.of(new Target(card, accountId, ChannelDefaults.forStaff(staff.get(accountId))));
        }
        return cards.byId(booking.clientId()).filter(card -> !card.erased()).map(card -> {
            var cardAccount = card.accountId();
            var defaults = cardAccount != null && staff.containsKey(cardAccount)
                    ? ChannelDefaults.forStaff(staff.get(cardAccount))
                    : ChannelDefaults.CLIENT;
            return new Target(card, cardAccount, defaults);
        });
    }

    /** Доступные получателю сейчас каналы, в порядке {@link Channel}. */
    Set<Channel> available(Target target) {
        var available = EnumSet.noneOf(Channel.class);
        var switches = switches(target);
        var accountId = target.accountId();
        for (var channel : Channel.values()) {
            if (!configured(channel) || !enabled(switches, channel)) {
                continue;
            }
            var hasAddress = switch (channel) {
                case EMAIL -> target.email() != null;
                case TELEGRAM ->
                    accountId != null
                            && !accountChannels
                                    .linkedToTelegram(List.of(accountId))
                                    .isEmpty();
            };
            if (hasAddress) {
                available.add(channel);
            }
        }
        return available;
    }

    /**
     * Адрес получателя в канале на момент отправки — по тем же правилам, что {@link #available}; пусто — канал
     * получателю сейчас недоступен.
     */
    Optional<ResolvedRecipient> resolve(Channel channel, Target target) {
        if (!configured(channel) || !enabled(switches(target), channel)) {
            return Optional.empty();
        }
        var card = target.card();
        var name = card == null ? null : card.firstName();
        var accountId = target.accountId();
        return switch (channel) {
            case EMAIL -> Optional.ofNullable(target.email()).map(email -> new ResolvedRecipient(email, null, name));
            case TELEGRAM ->
                accountId == null
                        ? Optional.empty()
                        : accountChannels.telegramChat(accountId).map(chat -> new ResolvedRecipient(null, chat, name));
        };
    }

    /** Реализация канала, если он настроен на сервере. */
    Optional<NotificationChannel> channel(Channel channel) {
        return Optional.ofNullable(channels.get(channel)).filter(NotificationChannel::configured);
    }

    private boolean configured(Channel channel) {
        return channel(channel).isPresent();
    }

    /** Своя настройка аккаунта получателя или умолчание. */
    private ChannelSwitches switches(Target target) {
        var accountId = target.accountId();
        if (accountId == null) {
            return target.defaults();
        }
        var own = accountChannels.switches(List.of(accountId)).get(accountId);
        return own != null ? own : target.defaults();
    }

    private static boolean enabled(ChannelSwitches switches, Channel channel) {
        return switch (channel) {
            case EMAIL -> switches.email();
            case TELEGRAM -> switches.telegram();
        };
    }

    /**
     * Получатель на текущий момент. {@link #toString()} личных данных карточки не показывает.
     *
     * @param card карточка: клиентки — текущая карточка записи; сотрудника — карточка его аккаунта, если есть
     * @param accountId аккаунт: сотрудника или карточки клиентки; пусто — карточка без аккаунта
     * @param defaults каналы без своей настройки аккаунта
     */
    record Target(@Nullable ClientView card, @Nullable UUID accountId, ChannelSwitches defaults) {

        /** Почта карточки; пусто — нет карточки или почты. */
        @Nullable
        String email() {
            if (card == null) {
                return null;
            }
            var email = card.email();
            return email == null || email.isBlank() ? null : email;
        }

        @Override
        public String toString() {
            return "Target[card=" + (card == null ? "<none>" : card.id()) + ", accountId=" + accountId + ", defaults="
                    + defaults + "]";
        }
    }
}
