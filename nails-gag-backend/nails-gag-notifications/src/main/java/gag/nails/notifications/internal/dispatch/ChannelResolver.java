package gag.nails.notifications.internal.dispatch;

import gag.nails.booking.model.BookingView;
import gag.nails.clients.ClientQueries;
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

    private final ClientQueries clients;
    private final StaffDirectory staffDirectory;
    private final AccountChannels accountChannels;
    private final Map<Channel, NotificationChannel> channels;

    ChannelResolver(
            ClientQueries clients,
            StaffDirectory staffDirectory,
            AccountChannels accountChannels,
            List<NotificationChannel> channels) {
        this.clients = clients;
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
     * Получатели мастерских сообщений с кодами их ролей — один запрос на пачку сообщений: от этого списка зависят и
     * мастерские сообщения, и умолчания каналов аккаунтов сотрудников.
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
     * @param staff получатели мастерских сообщений ({@link #staff()})
     */
    Optional<Target> target(
            MessageKind kind, @Nullable UUID accountId, BookingView booking, Map<UUID, Set<String>> staff) {
        if (kind.name().startsWith("STAFF_")) {
            if (accountId == null || !staff.containsKey(accountId)) {
                return Optional.empty();
            }
            var card = clients.byAccountId(accountId).orElse(null);
            return Optional.of(new Target(card, accountId, ChannelDefaults.forStaff(staff.get(accountId))));
        }
        return clients.byId(booking.clientId()).filter(card -> !card.erased()).map(card -> {
            var cardAccount = card.accountId();
            var defaults = cardAccount != null && staff.containsKey(cardAccount)
                    ? ChannelDefaults.forStaff(staff.get(cardAccount))
                    : ChannelDefaults.CLIENT;
            return new Target(card, cardAccount, defaults);
        });
    }

    /**
     * Доступные получателю каналы, в порядке {@link Channel}.
     *
     * @param switches свои настройки каналов аккаунтов ({@link AccountChannels#switches})
     * @param linked аккаунты с привязанным ботом ({@link AccountChannels#linkedToTelegram})
     */
    Set<Channel> available(Target target, Map<UUID, ChannelSwitches> switches, Set<UUID> linked) {
        var available = EnumSet.noneOf(Channel.class);
        var enabled = target.switches(switches);
        for (var channel : Channel.values()) {
            var hasAddress = switch (channel) {
                case EMAIL -> target.email() != null;
                case TELEGRAM -> target.accountId() != null && linked.contains(target.accountId());
            };
            if (configured(channel) && enabled(enabled, channel) && hasAddress) {
                available.add(channel);
            }
        }
        return available;
    }

    /**
     * Адрес получателя в канале на момент отправки — по тем же правилам, что {@link #available}, но по текущим
     * настройке и привязке; пусто — канал получателю сейчас недоступен.
     */
    Optional<ResolvedRecipient> resolve(Channel channel, Target target) {
        if (!configured(channel)) {
            return Optional.empty();
        }
        var accountId = target.accountId();
        var own = accountId == null ? Map.<UUID, ChannelSwitches>of() : accountChannels.switches(List.of(accountId));
        if (!enabled(target.switches(own), channel)) {
            return Optional.empty();
        }
        var name = target.card() == null ? null : target.card().firstName();
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

    private static boolean enabled(ChannelSwitches switches, Channel channel) {
        return switch (channel) {
            case EMAIL -> switches.email();
            case TELEGRAM -> switches.telegram();
        };
    }

    /**
     * Получатель на текущий момент.
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

        /** Каналы получателя: своя настройка аккаунта или умолчание. */
        ChannelSwitches switches(Map<UUID, ChannelSwitches> own) {
            var configured = accountId == null ? null : own.get(accountId);
            return configured != null ? configured : defaults;
        }
    }
}
