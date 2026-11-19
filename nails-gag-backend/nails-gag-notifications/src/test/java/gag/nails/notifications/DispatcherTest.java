package gag.nails.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import gag.nails.booking.BookingPrepayments;
import gag.nails.booking.EarlierTimeOffers;
import gag.nails.booking.model.BookingView;
import gag.nails.clients.command.UpdateClient;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.InternalException;
import gag.nails.common.id.UuidV7;
import gag.nails.notifications.command.UpdateMyChannels;
import gag.nails.notifications.internal.JobsLifecycle;
import gag.nails.notifications.internal.dispatch.Dispatcher;
import gag.nails.notifications.internal.dispatch.ResolvedRecipient;
import gag.nails.notifications.internal.dispatch.SendResult;
import gag.nails.notifications.model.Channel;
import gag.nails.notifications.model.DeliveryStatus;
import gag.nails.notifications.model.MessageKind;
import gag.nails.notifications.model.MessageStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Отправка (спека §3.2, §3.5, §5.2) с подменными каналами почты и Telegram: настоящие записи и карточки, прогон
 * отправки — так же, как задача по расписанию. Прогон берёт и чужие созревшие сообщения, поэтому проверки — только по
 * своим записям, сообщениям и адресам. Часы — четверг 1 октября 2026, 09:00 по Москве; визиты — в понедельник
 * 5 октября.
 */
@NotificationsModuleTest
@Import(DispatcherTest.Channels.class)
class DispatcherTest extends QueueTestSupport {

    @Autowired
    Dispatcher dispatcher;

    @Autowired
    FakeChannel fakeEmail;

    @Autowired
    FakeChannel fakeTelegram;

    @Autowired
    NotificationPreferences preferences;

    @Autowired
    BookingPrepayments prepayments;

    @Autowired
    EarlierTimeOffers earlierTimes;

    @Autowired
    JobsLifecycle lifecycle;

    /** Триггер теста, отвергающий вставку доставок; снимается после теста. */
    private @Nullable String failureTrigger;

    @AfterEach
    void resetChannels() {
        lifecycle.start();
        fakeEmail.reset();
        fakeTelegram.reset();
        if (failureTrigger != null) {
            jdbc.sql("DROP TRIGGER IF EXISTS %1$s ON notifications.delivery".formatted(failureTrigger))
                    .update();
            jdbc.sql("DROP FUNCTION IF EXISTS notifications.%s()".formatted(failureTrigger))
                    .update();
            failureTrigger = null;
        }
    }

    @Test
    void clientGetsEmailAndTelegramByDefault() {
        var email = email();
        var chat = chat();
        var anna = registeredClient("Анна", email);
        linkTelegram(anna.accountId(), chat);
        var booking = bookByMaster(anna.clientId(), slot(MONDAY, "14:00"), false);

        dispatcher.run();

        var messageId = messageId(booking.id(), MessageKind.BOOKED_BY_MASTER);
        assertThat(status(messageId)).isEqualTo(MessageStatus.DISPATCHED);
        assertThat(deliveries(messageId))
                .extracting(Delivery::channel, Delivery::status, Delivery::attempts, Delivery::sentAt)
                .containsExactlyInAnyOrder(
                        tuple(Channel.EMAIL, DeliveryStatus.SENT, 1, clock.instant()),
                        tuple(Channel.TELEGRAM, DeliveryStatus.SENT, 1, clock.instant()));
        var letter = fakeEmail.toEmail(email);
        assertThat(letter).singleElement().satisfies(attempt -> {
            assertThat(attempt.recipient()).isEqualTo(new ResolvedRecipient(email, null, "Анна"));
            assertThat(attempt.text().subject()).isEqualTo("Вы записаны на пн, 5 октября, 14:00");
            assertThat(attempt.text().body())
                    .startsWith("Вы записаны на пн, 5 октября, 14:00\nАнна, мастер записал вас на визит.")
                    .contains("\nУслуги: Маникюр\n");
        });
        assertThat(fakeTelegram.toChat(chat)).singleElement().satisfies(attempt -> {
            assertThat(attempt.recipient()).isEqualTo(new ResolvedRecipient(null, chat, "Анна"));
            assertThat(attempt.text()).isEqualTo(letter.getFirst().text());
        });
    }

    @Test
    void cardWithoutAccountGetsEmailOnly() {
        var email = email();
        var anna = client("Анна", null, email);
        var booking = bookByMaster(anna, slot(MONDAY, "14:00"), false);

        dispatcher.run();

        assertThat(deliveries(messageId(booking.id(), MessageKind.BOOKED_BY_MASTER)))
                .extracting(Delivery::channel, Delivery::status)
                .containsExactly(tuple(Channel.EMAIL, DeliveryStatus.SENT));
        assertThat(fakeEmail.toEmail(email)).hasSize(1);
    }

    @Test
    void messageWithoutChannelsIsDispatchedWithoutDeliveries() {
        var anna = client("Анна");
        var booking = bookByMaster(anna, slot(MONDAY, "14:00"), false);

        dispatcher.run();

        var messageId = messageId(booking.id(), MessageKind.BOOKED_BY_MASTER);
        assertThat(status(messageId)).isEqualTo(MessageStatus.DISPATCHED);
        assertThat(deliveries(messageId)).isEmpty();
    }

    @Test
    void channelSwitchedOffByClientIsSkipped() {
        var email = email();
        var chat = chat();
        var anna = registeredClient("Анна", email);
        linkTelegram(anna.accountId(), chat);
        preferences.update(new UpdateMyChannels(false, true), Actor.of(anna.accountId(), anna.clientId(), Map.of()));
        var booking = bookByMaster(anna.clientId(), slot(MONDAY, "14:00"), false);

        dispatcher.run();

        assertThat(deliveries(messageId(booking.id(), MessageKind.BOOKED_BY_MASTER)))
                .extracting(Delivery::channel)
                .containsExactly(Channel.TELEGRAM);
        assertThat(fakeEmail.toEmail(email)).isEmpty();
        assertThat(fakeTelegram.toChat(chat)).hasSize(1);
    }

    @Test
    void channelNotConfiguredOnServerIsUnavailable() {
        fakeTelegram.configured(false);
        var email = email();
        var chat = chat();
        var anna = registeredClient("Анна", email);
        linkTelegram(anna.accountId(), chat);
        var booking = bookByMaster(anna.clientId(), slot(MONDAY, "14:00"), false);

        dispatcher.run();

        assertThat(deliveries(messageId(booking.id(), MessageKind.BOOKED_BY_MASTER)))
                .extracting(Delivery::channel)
                .containsExactly(Channel.EMAIL);
        assertThat(fakeTelegram.toChat(chat)).isEmpty();
    }

    /**
     * Умолчания сотрудников: мастеру — только Telegram, хотя у его карточки есть почта; разработчику — ничего. Карточка
     * клиентки записи без почты и аккаунта — её сообщение уходит без доставок.
     */
    @Test
    void staffDefaultsFollowRoles() {
        var alina = staffAccount("MASTER");
        var alinaEmail = email();
        var alinaChat = chat();
        cardOf(alina, "Алина", alinaEmail);
        linkTelegram(alina, alinaChat);
        var developer = staffAccount("DEVELOPER");
        var developerEmail = email();
        var developerChat = chat();
        cardOf(developer, "Дмитрий", developerEmail);
        linkTelegram(developer, developerChat);
        var maria = client("Мария", "Петрова", null);
        var booking = bookByClient(maria, slot(MONDAY, "14:00"));

        dispatcher.run();

        assertThat(deliveries(staffMessageId(booking.id(), alina)))
                .extracting(Delivery::channel, Delivery::status)
                .containsExactly(tuple(Channel.TELEGRAM, DeliveryStatus.SENT));
        assertThat(fakeTelegram.toChat(alinaChat))
                .singleElement()
                .satisfies(attempt -> assertThat(attempt.text().body())
                        .startsWith("Новая запись: пн, 5 октября, 14:00\nКлиентка: Мария Петрова\n"));
        assertThat(fakeEmail.toEmail(alinaEmail)).isEmpty();
        var developerMessage = staffMessageId(booking.id(), developer);
        assertThat(status(developerMessage)).isEqualTo(MessageStatus.DISPATCHED);
        assertThat(deliveries(developerMessage)).isEmpty();
        var clientMessage = messageId(booking.id(), MessageKind.BOOKING_RECEIVED);
        assertThat(status(clientMessage)).isEqualTo(MessageStatus.DISPATCHED);
        assertThat(deliveries(clientMessage)).isEmpty();
    }

    @Test
    void staffWithOwnSettingsGetsChosenChannels() {
        var alina = staffAccount("MASTER");
        var alinaEmail = email();
        var alinaCard = cardOf(alina, "Алина", alinaEmail);
        preferences.update(new UpdateMyChannels(true, false), Actor.of(alina, alinaCard, Actor.masterAccess()));
        var booking = bookByClient(client("Мария"), slot(MONDAY, "14:00"));

        dispatcher.run();

        assertThat(deliveries(staffMessageId(booking.id(), alina)))
                .extracting(Delivery::channel, Delivery::status)
                .containsExactly(tuple(Channel.EMAIL, DeliveryStatus.SENT));
        assertThat(fakeEmail.toEmail(alinaEmail)).hasSize(1);
    }

    @Test
    void prepaymentRequestOfConfirmedBookingIsCancelled() {
        var email = email();
        var anna = client("Анна", null, email);
        var booking = bookByMaster(anna, slot(MONDAY, "14:00"), true);
        prepayments.markReceived(booking.id(), master);
        awaitProcessed(booking.id());

        dispatcher.run();

        var request = messageId(booking.id(), MessageKind.PREPAYMENT_REQUESTED);
        assertThat(status(request)).isEqualTo(MessageStatus.CANCELLED);
        assertThat(deliveries(request)).isEmpty();
        assertThat(status(messageId(booking.id(), MessageKind.CONFIRMED))).isEqualTo(MessageStatus.DISPATCHED);
        assertThat(fakeEmail.toEmail(email))
                .extracting(attempt -> attempt.text().subject())
                .containsExactly("Запись подтверждена: пн, 5 октября, 14:00");
    }

    @Test
    void pendingPrepaymentRequestIsSent() {
        var email = email();
        var anna = client("Анна", null, email);
        var booking = bookByMaster(anna, slot(MONDAY, "14:00"), true);

        dispatcher.run();

        assertThat(status(messageId(booking.id(), MessageKind.PREPAYMENT_REQUESTED)))
                .isEqualTo(MessageStatus.DISPATCHED);
        assertThat(fakeEmail.toEmail(email))
                .singleElement()
                .satisfies(attempt -> assertThat(attempt.text().body())
                        .contains("Как внести предоплату — уточните у мастера: @gagushichewa"));
    }

    /** Напоминание актуально, пока запись подтверждена и визит начинается тогда же, когда при расчёте. */
    @Test
    void reminderForAnotherVisitStartIsCancelled() {
        var email = email();
        var anna = client("Анна", null, email);
        var slot = slot(MONDAY, "14:00");
        var booking = bookByMaster(anna, slot, false);
        var now = clock.instant();
        var current = insertMessage(MessageKind.REMINDER_HOURS_BEFORE, anna, booking.id(), now, slot.startsAt(), now);
        var stale = insertMessage(
                MessageKind.REMINDER_DAY_BEFORE,
                anna,
                booking.id(),
                now,
                slot.startsAt().plusSeconds(3600),
                now);

        dispatcher.run();

        assertThat(status(current)).isEqualTo(MessageStatus.DISPATCHED);
        assertThat(status(stale)).isEqualTo(MessageStatus.CANCELLED);
        assertThat(deliveries(stale)).isEmpty();
    }

    @Test
    void reminderOfBookingAwaitingPrepaymentIsCancelled() {
        var anna = client("Анна", null, email());
        var slot = slot(MONDAY, "14:00");
        var booking = bookByMaster(anna, slot, true);
        var now = clock.instant();
        var reminder = insertMessage(MessageKind.REMINDER_HOURS_BEFORE, anna, booking.id(), now, slot.startsAt(), now);

        dispatcher.run();

        assertThat(status(reminder)).isEqualTo(MessageStatus.CANCELLED);
    }

    @Test
    void messageOfMissingBookingIsCancelled() {
        var anna = client("Анна", null, email());
        var now = clock.instant();
        var message = insertMessage(MessageKind.CONFIRMED, anna, bookingId(), now, now.plusSeconds(3600), now);

        dispatcher.run();

        assertThat(status(message)).isEqualTo(MessageStatus.CANCELLED);
        assertThat(deliveries(message)).isEmpty();
    }

    /** Отозванное предложение не отправляется, даже если по записи уже ждёт ответа следующее. */
    @Test
    void withdrawnEarlierOfferIsCancelledAndPendingOneIsSent() {
        var email = email();
        var anna = client("Анна", null, email);
        var slot = slot(MONDAY, "14:00");
        var booking = bookByMaster(anna, slot, false);
        earlierTimes.propose(booking.id(), slot.startsAt().minusSeconds(3600), master);
        awaitProcessed(booking.id());
        earlierTimes.withdraw(booking.id(), master);
        clock.advance(Duration.ofMinutes(1));
        earlierTimes.propose(booking.id(), slot.startsAt().minusSeconds(1800), master);
        awaitProcessed(booking.id());

        dispatcher.run();

        assertThat(earlierProposals(booking.id())).containsExactly(MessageStatus.CANCELLED, MessageStatus.DISPATCHED);
        assertThat(fakeEmail.toEmail(email))
                .extracting(attempt -> attempt.text().subject())
                .contains("Можно прийти раньше: пн, 5 октября, 13:30")
                .doesNotContain("Можно прийти раньше: пн, 5 октября, 13:00");
    }

    /** Паузы между попытками — 1, 5, 30 и 120 минут; пятая неудача — {@code transient_exhausted}. */
    @Test
    void transientFailuresAreRetriedWithPausesUntilExhausted() {
        var email = email();
        fakeEmail.respond((recipient, text) ->
                email.equals(recipient.email()) ? new SendResult.Retry(null, "smtp_down") : new SendResult.Sent());
        var booking = bookByMaster(client("Анна", null, email), slot(MONDAY, "14:00"), false);
        var messageId = messageId(booking.id(), MessageKind.BOOKED_BY_MASTER);

        for (var pause : List.of(1, 5, 30, 120)) {
            dispatcher.run();
            var delivery = deliveries(messageId).getFirst();
            assertThat(delivery.status()).isEqualTo(DeliveryStatus.PENDING);
            assertThat(delivery.errorCode()).isEqualTo("smtp_down");
            assertThat(delivery.nextAttemptAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(pause)));

            clock.advance(Duration.ofMinutes(pause).minusSeconds(1));
            dispatcher.run();
            clock.advance(Duration.ofSeconds(1));
        }
        assertThat(fakeEmail.toEmail(email)).hasSize(4);

        dispatcher.run();

        assertThat(deliveries(messageId))
                .extracting(Delivery::status, Delivery::attempts, Delivery::errorCode)
                .containsExactly(tuple(DeliveryStatus.FAILED, 5, "transient_exhausted"));
        assertThat(fakeEmail.toEmail(email)).hasSize(5);
    }

    @Test
    void deliveryPastMessageExpiryFails() {
        var email = email();
        fakeEmail.respond((recipient, text) ->
                email.equals(recipient.email()) ? new SendResult.Retry(null, "smtp_down") : new SendResult.Sent());
        var booking = bookByMaster(client("Анна", null, email), slot(MONDAY, "14:00"), false);
        dispatcher.run();

        clock.advance(Duration.ofHours(25));
        dispatcher.run();

        assertThat(deliveries(messageId(booking.id(), MessageKind.BOOKED_BY_MASTER)))
                .extracting(Delivery::status, Delivery::attempts, Delivery::errorCode)
                .containsExactly(tuple(DeliveryStatus.FAILED, 1, "expired"));
        assertThat(fakeEmail.toEmail(email)).hasSize(1);
    }

    @Test
    void retryThatWouldNotMakeItBeforeExpiryFails() {
        var email = email();
        fakeEmail.respond((recipient, text) -> email.equals(recipient.email())
                ? new SendResult.Retry(Duration.ofHours(30), "smtp_down")
                : new SendResult.Sent());
        var booking = bookByMaster(client("Анна", null, email), slot(MONDAY, "14:00"), false);

        dispatcher.run();

        assertThat(deliveries(messageId(booking.id(), MessageKind.BOOKED_BY_MASTER)))
                .extracting(Delivery::status, Delivery::errorCode)
                .containsExactly(tuple(DeliveryStatus.FAILED, "expired"));
    }

    /** Канал просит подождать дольше паузы (Telegram 429 {@code retry_after}) — следующая попытка не раньше. */
    @Test
    void retryNotBeforeIsRespected() {
        var chat = chat();
        var anna = registeredClient("Анна", email());
        linkTelegram(anna.accountId(), chat);
        var attempts = new AtomicInteger();
        fakeTelegram.respond(
                (recipient, text) -> Long.valueOf(chat).equals(recipient.chatId()) && attempts.getAndIncrement() == 0
                        ? new SendResult.Retry(Duration.ofMinutes(10), "telegram_throttled")
                        : new SendResult.Sent());
        var booking = bookByMaster(anna.clientId(), slot(MONDAY, "14:00"), false);
        var messageId = messageId(booking.id(), MessageKind.BOOKED_BY_MASTER);

        dispatcher.run();

        assertThat(delivery(messageId, Channel.TELEGRAM).nextAttemptAt())
                .isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));

        clock.advance(Duration.ofMinutes(5));
        dispatcher.run();
        assertThat(fakeTelegram.toChat(chat)).hasSize(1);

        clock.advance(Duration.ofMinutes(5));
        dispatcher.run();
        assertThat(fakeTelegram.toChat(chat)).hasSize(2);
        assertThat(delivery(messageId, Channel.TELEGRAM).status()).isEqualTo(DeliveryStatus.SENT);
    }

    /** Бот заблокирован: доставка неудачна, привязка снята; почта того же сообщения уходит как обычно. */
    @Test
    void blockedBotUnlinksChatWithoutAffectingEmail() {
        var email = email();
        var chat = chat();
        var anna = registeredClient("Анна", email);
        linkTelegram(anna.accountId(), chat);
        fakeTelegram.respond((recipient, text) -> Long.valueOf(chat).equals(recipient.chatId())
                ? new SendResult.Fail("telegram_blocked", true)
                : new SendResult.Sent());
        var booking = bookByMaster(anna.clientId(), slot(MONDAY, "14:00"), false);

        dispatcher.run();

        assertThat(deliveries(messageId(booking.id(), MessageKind.BOOKED_BY_MASTER)))
                .extracting(Delivery::channel, Delivery::status, Delivery::errorCode)
                .containsExactlyInAnyOrder(
                        tuple(Channel.EMAIL, DeliveryStatus.SENT, null),
                        tuple(Channel.TELEGRAM, DeliveryStatus.FAILED, "telegram_blocked"));
        assertThat(telegramChat(anna.accountId())).isNull();
    }

    /** Клиентка привязала бота заново, пока шла отправка в прежний чат: новая привязка остаётся. */
    @Test
    void blockedBotKeepsBindingMadeAgainDuringSending() {
        var chat = chat();
        var newChat = chat();
        var anna = registeredClient("Анна", email());
        linkTelegram(anna.accountId(), chat);
        fakeTelegram.respond((recipient, text) -> {
            if (!Long.valueOf(chat).equals(recipient.chatId())) {
                return new SendResult.Sent();
            }
            jdbc.sql("delete from notifications.telegram_chat where account_id = ?")
                    .param(anna.accountId())
                    .update();
            linkTelegram(anna.accountId(), newChat);
            return new SendResult.Fail("telegram_blocked", true);
        });
        bookByMaster(anna.clientId(), slot(MONDAY, "14:00"), false);

        dispatcher.run();

        assertThat(telegramChat(anna.accountId())).isEqualTo(newChat);
    }

    @Test
    void permanentEmailRejectionDoesNotStopTelegram() {
        var email = email();
        var chat = chat();
        var anna = registeredClient("Анна", email);
        linkTelegram(anna.accountId(), chat);
        fakeEmail.respond((recipient, text) ->
                email.equals(recipient.email()) ? new SendResult.Fail("email_rejected", false) : new SendResult.Sent());
        var booking = bookByMaster(anna.clientId(), slot(MONDAY, "14:00"), false);

        dispatcher.run();

        assertThat(deliveries(messageId(booking.id(), MessageKind.BOOKED_BY_MASTER)))
                .extracting(Delivery::channel, Delivery::status, Delivery::errorCode)
                .containsExactlyInAnyOrder(
                        tuple(Channel.EMAIL, DeliveryStatus.FAILED, "email_rejected"),
                        tuple(Channel.TELEGRAM, DeliveryStatus.SENT, null));
        assertThat(telegramChat(anna.accountId())).isEqualTo(chat);
    }

    @Test
    void channelExceptionIsTransientFailure() {
        var email = email();
        fakeEmail.respond((recipient, text) -> {
            if (email.equals(recipient.email())) {
                throw new InternalException("smtp exploded for " + recipient.email());
            }
            return new SendResult.Sent();
        });
        var booking = bookByMaster(client("Анна", null, email), slot(MONDAY, "14:00"), false);

        dispatcher.run();

        assertThat(deliveries(messageId(booking.id(), MessageKind.BOOKED_BY_MASTER)))
                .extracting(Delivery::status, Delivery::attempts, Delivery::errorCode)
                .containsExactly(tuple(DeliveryStatus.PENDING, 1, "channel_error"));
    }

    /**
     * Доставка берётся в аренду прямо перед своей отправкой: попытка засчитана и {@code next_attempt_at} сдвинут на 5
     * минут до вызова канала, и это зафиксировано, а остальные доставки пачки ещё не тронуты. Второй прогон, запущенный
     * посреди отправки первого (из другого потока), взятую доставку не видит и не отправляет повторно, а остальные
     * отправляет сам — первый прогон их уже не берёт.
     */
    @Test
    void runDuringSendingDoesNotSendLeasedDeliveriesAgain() throws Exception {
        var emails = List.of(email(), email(), email());
        var days = List.of(MONDAY, TUESDAY, TUESDAY.plusDays(1));
        var bookings = List.of(0, 1, 2).stream()
                .map(i -> bookByMaster(client("Анна", null, emails.get(i)), slot(days.get(i), "14:00"), false))
                .toList();
        var lease = Timestamp.from(clock.instant().plus(Duration.ofMinutes(5)));
        var leasedSeen = new AtomicInteger(-1);
        var nested = new AtomicBoolean();
        fakeEmail.respond((recipient, text) -> {
            if (emails.contains(recipient.email()) && nested.compareAndSet(false, true)) {
                leasedSeen.set(jdbc.sql("""
                                select count(*) from notifications.delivery d
                                join notifications.message m on m.id = d.message_id
                                where m.booking_id in (:bookings) and d.status = 'PENDING' and d.attempts = 1
                                    and d.next_attempt_at = :lease
                                """)
                        .param(
                                "bookings",
                                bookings.stream().map(BookingView::id).toList())
                        .param("lease", lease)
                        .query(Integer.class)
                        .single());
                CompletableFuture.runAsync(dispatcher::run).join();
            }
            return new SendResult.Sent();
        });

        dispatcher.run();

        assertThat(leasedSeen).hasValue(1);
        for (var email : emails) {
            assertThat(fakeEmail.toEmail(email)).hasSize(1);
        }
        for (var booking : bookings) {
            assertThat(deliveries(messageId(booking.id(), MessageKind.BOOKED_BY_MASTER)))
                    .extracting(Delivery::status, Delivery::attempts)
                    .containsExactly(tuple(DeliveryStatus.SENT, 1));
        }
    }

    /**
     * Остановка приложения посреди отправки: начатая доставка отправляется и её результат записывается, следующая не
     * берётся — ни попытки, ни аренды, она созревшая до следующего запуска, который её и отправляет.
     */
    @Test
    void stopDuringSendingFinishesCurrentDeliveryAndClaimsNoMore() {
        var emails = List.of(email(), email());
        var days = List.of(MONDAY, TUESDAY);
        var bookings = List.of(0, 1).stream()
                .map(i -> bookByMaster(client("Анна", null, emails.get(i)), slot(days.get(i), "14:00"), false))
                .toList();
        var due = clock.instant();
        fakeEmail.respond((recipient, text) -> {
            if (emails.contains(recipient.email())) {
                lifecycle.stop();
            }
            return new SendResult.Sent();
        });

        dispatcher.run();

        var deliveries = bookings.stream()
                .map(booking -> deliveries(messageId(booking.id(), MessageKind.BOOKED_BY_MASTER))
                        .getFirst())
                .toList();
        assertThat(deliveries)
                .extracting(Delivery::status, Delivery::attempts, Delivery::nextAttemptAt, Delivery::sentAt)
                .containsExactlyInAnyOrder(
                        tuple(DeliveryStatus.SENT, 1, due.plus(Duration.ofMinutes(5)), due),
                        tuple(DeliveryStatus.PENDING, 0, due, null));
        assertThat(emails.stream().mapToInt(email -> fakeEmail.toEmail(email).size()))
                .containsExactlyInAnyOrder(1, 0);

        lifecycle.start();
        fakeEmail.respond(FakeChannel::sent);
        dispatcher.run();

        for (var email : emails) {
            assertThat(fakeEmail.toEmail(email)).hasSize(1);
        }
    }

    /** После остановки приложения созревшие сообщения не берутся: остаются {@code PENDING} без доставок. */
    @Test
    void stoppedDispatcherClaimsNoMessages() {
        var email = email();
        var booking = bookByMaster(client("Анна", null, email), slot(MONDAY, "14:00"), false);
        var messageId = messageId(booking.id(), MessageKind.BOOKED_BY_MASTER);

        lifecycle.stop();
        dispatcher.run();

        assertThat(status(messageId)).isEqualTo(MessageStatus.PENDING);
        assertThat(deliveries(messageId)).isEmpty();
        assertThat(fakeEmail.toEmail(email)).isEmpty();

        lifecycle.start();
        dispatcher.run();

        assertThat(status(messageId)).isEqualTo(MessageStatus.DISPATCHED);
        assertThat(fakeEmail.toEmail(email)).hasSize(1);
    }

    @Test
    void simultaneousRunsSendEachDeliveryOnce() throws Exception {
        var emails = List.of(email(), email(), email());
        var days = List.of(MONDAY, TUESDAY, TUESDAY.plusDays(1));
        for (var i = 0; i < emails.size(); i++) {
            bookByMaster(client("Анна", null, emails.get(i)), slot(days.get(i), "14:00"), false);
        }
        fakeEmail.delay(Duration.ofMillis(100));
        var barrier = new CyclicBarrier(2);
        Runnable run = () -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            dispatcher.run();
        };

        CompletableFuture.allOf(CompletableFuture.runAsync(run), CompletableFuture.runAsync(run))
                .get(60, TimeUnit.SECONDS);

        for (var email : emails) {
            assertThat(fakeEmail.toEmail(email)).hasSize(1);
        }
    }

    /**
     * Адрес определяется при отправке: после обезличивания карточки повтор никуда не уходит — вторая попытка
     * засчитана, но канал не вызван.
     */
    @Test
    void retryAfterErasureGoesNowhere() {
        var email = email();
        var anna = client("Анна", null, email);
        var attempts = new AtomicInteger();
        fakeEmail.respond((recipient, text) -> email.equals(recipient.email()) && attempts.getAndIncrement() == 0
                ? new SendResult.Retry(null, "smtp_down")
                : new SendResult.Sent());
        var booking = bookByMaster(anna, slot(MONDAY, "14:00"), false);
        dispatcher.run();

        clients.erase(anna);
        clock.advance(Duration.ofMinutes(1));
        dispatcher.run();

        assertThat(deliveries(messageId(booking.id(), MessageKind.BOOKED_BY_MASTER)))
                .extracting(Delivery::status, Delivery::attempts, Delivery::errorCode)
                .containsExactly(tuple(DeliveryStatus.FAILED, 2, "unavailable"));
        assertThat(fakeEmail.toEmail(email)).hasSize(1);
    }

    @Test
    void retryGoesToCurrentEmail() {
        var email = email();
        var newEmail = email();
        var anna = client("Анна", null, email);
        fakeEmail.respond((recipient, text) ->
                email.equals(recipient.email()) ? new SendResult.Retry(null, "smtp_down") : new SendResult.Sent());
        var booking = bookByMaster(anna, slot(MONDAY, "14:00"), false);
        dispatcher.run();

        clients.update(anna, new UpdateClient("Анна", null, null, newEmail, null), master);
        clock.advance(Duration.ofMinutes(1));
        dispatcher.run();

        assertThat(fakeEmail.toEmail(newEmail)).hasSize(1);
        assertThat(deliveries(messageId(booking.id(), MessageKind.BOOKED_BY_MASTER)))
                .extracting(Delivery::status, Delivery::attempts)
                .containsExactly(tuple(DeliveryStatus.SENT, 2));
    }

    /**
     * Напоминание, рассчитанное одновременно с объединением карточек, осталось на удалённой карточке: получатель —
     * текущая карточка записи, и оно уходит оставшейся.
     */
    @Test
    void reminderLeftOnMergedCardGoesToSurvivor() {
        var survivorEmail = email();
        var removedEmail = email();
        var survivor = client("Анна", null, survivorEmail);
        var removed = client("Анна", null, removedEmail);
        var slot = slot(MONDAY, "14:00");
        var booking = bookByMaster(removed, slot, false);
        clients.merge(removed, survivor, master);
        var now = clock.instant();
        var reminder =
                insertMessage(MessageKind.REMINDER_HOURS_BEFORE, removed, booking.id(), now, slot.startsAt(), now);

        dispatcher.run();

        assertThat(deliveries(reminder))
                .extracting(Delivery::channel, Delivery::status)
                .containsExactly(tuple(Channel.EMAIL, DeliveryStatus.SENT));
        assertThat(fakeEmail.toEmail(survivorEmail))
                .extracting(attempt -> attempt.text().subject())
                .contains("Скоро визит: пн, 5 октября, 14:00");
        assertThat(fakeEmail.toEmail(removedEmail)).isEmpty();
    }

    /**
     * Тихие часы и при отправке: повтор, попавший в них, ждёт их конца; доставка клиентке, созревшая в тихие часы
     * (после простоя), не уходит до 9:00, а мастерская уходит сразу.
     */
    @Test
    void clientDeliveriesWaitForQuietHoursToEnd() {
        var nineNextDay = Instant.parse("2026-10-02T06:00:00Z");
        clock.set(Instant.parse("2026-10-01T18:50:00Z"));
        var email = email();
        var attempts = new AtomicInteger();
        fakeEmail.respond((recipient, text) -> email.equals(recipient.email()) && attempts.getAndIncrement() == 0
                ? new SendResult.Retry(Duration.ofMinutes(15), "smtp_down")
                : new SendResult.Sent());
        var booking = bookByMaster(client("Анна", null, email), slot(MONDAY, "14:00"), false);
        var messageId = messageId(booking.id(), MessageKind.BOOKED_BY_MASTER);

        dispatcher.run();

        assertThat(delivery(messageId, Channel.EMAIL).nextAttemptAt()).isEqualTo(nineNextDay);

        clock.set(Instant.parse("2026-10-01T20:00:00Z"));
        jdbc.sql("update notifications.delivery set next_attempt_at = ? where message_id = ?")
                .params(Timestamp.from(Instant.parse("2026-10-01T19:30:00Z")), messageId)
                .update();
        var alina = staffAccount("MASTER");
        var alinaChat = chat();
        linkTelegram(alina, alinaChat);
        var late = bookByClient(client("Мария"), slot(TUESDAY, "14:00"));

        dispatcher.run();

        assertThat(fakeEmail.toEmail(email)).hasSize(1);
        assertThat(delivery(messageId, Channel.EMAIL))
                .extracting(Delivery::status, Delivery::attempts, Delivery::nextAttemptAt)
                .containsExactly(DeliveryStatus.PENDING, 1, nineNextDay);
        assertThat(delivery(staffMessageId(late.id(), alina), Channel.TELEGRAM).status())
                .isEqualTo(DeliveryStatus.SENT);

        clock.set(nineNextDay);
        dispatcher.run();

        assertThat(fakeEmail.toEmail(email)).hasSize(2);
        assertThat(delivery(messageId, Channel.EMAIL).status()).isEqualTo(DeliveryStatus.SENT);
    }

    /**
     * При равном сроке сообщения уходят в порядке событий, а не постановки в очередь: сообщение о более позднем событии
     * поставлено первым (меньший id).
     */
    @Test
    void messagesDueTogetherGoInOrderOfTheirEvents() {
        var email = email();
        var anna = client("Анна", null, email);
        var booking = bookByMaster(anna, slot(MONDAY, "14:00"), false);
        dispatcher.run();
        var now = clock.instant();
        insertMessage(MessageKind.CONFIRMED, anna, booking.id(), now, now.plusSeconds(3600), now.minusSeconds(10));
        insertMessage(MessageKind.RESCHEDULED, anna, booking.id(), now, now.plusSeconds(3600), now.minusSeconds(20));

        dispatcher.run();

        assertThat(fakeEmail.toEmail(email))
                .extracting(attempt -> attempt.text().subject())
                .containsExactly(
                        "Вы записаны на пн, 5 октября, 14:00",
                        "Запись перенесена на пн, 5 октября, 14:00",
                        "Запись подтверждена: пн, 5 октября, 14:00");
    }

    /**
     * Сообщение, на котором постановка доставок падает (здесь — триггер на вставку его доставок), не останавливает ни
     * остальные сообщения, ни отправку уже созревших доставок; оно остаётся {@code PENDING} и, истёкши, отменяется,
     * не доходя до вставки доставок.
     */
    @Test
    void messageThatFailsToDispatchStopsNeitherOthersNorSending() {
        var pendingEmail = email();
        var badEmail = email();
        var goodEmail = email();
        var pendingAttempts = new AtomicInteger();
        fakeEmail.respond(
                (recipient, text) -> pendingEmail.equals(recipient.email()) && pendingAttempts.getAndIncrement() == 0
                        ? new SendResult.Retry(null, "smtp_down")
                        : new SendResult.Sent());
        var pending = bookByMaster(client("Анна", null, pendingEmail), slot(MONDAY, "14:00"), false);
        dispatcher.run();
        clock.advance(Duration.ofMinutes(1));
        var bad = bookByMaster(client("Мария", null, badEmail), slot(TUESDAY, "14:00"), false);
        var good = bookByMaster(client("Ольга", null, goodEmail), slot(TUESDAY.plusDays(1), "14:00"), false);
        var badMessage = messageId(bad.id(), MessageKind.BOOKED_BY_MASTER);
        failDeliveryInsertsOf(badMessage);

        dispatcher.run();

        assertThat(status(badMessage)).isEqualTo(MessageStatus.PENDING);
        assertThat(deliveries(badMessage)).isEmpty();
        assertThat(status(messageId(good.id(), MessageKind.BOOKED_BY_MASTER))).isEqualTo(MessageStatus.DISPATCHED);
        assertThat(fakeEmail.toEmail(goodEmail)).hasSize(1);
        assertThat(delivery(messageId(pending.id(), MessageKind.BOOKED_BY_MASTER), Channel.EMAIL))
                .extracting(Delivery::status, Delivery::attempts)
                .containsExactly(DeliveryStatus.SENT, 2);

        clock.advance(Duration.ofHours(25));
        dispatcher.run();

        assertThat(status(badMessage)).isEqualTo(MessageStatus.CANCELLED);
        assertThat(fakeEmail.toEmail(badEmail)).isEmpty();
    }

    /** Аренду перехватили (отправка шла дольше, доставку взял другой прогон) — исход не пишется. */
    @Test
    void resultOfLostLeaseIsNotRecorded() {
        var email = email();
        var moved = clock.instant().plus(Duration.ofHours(1));
        var messageId = new AtomicReference<UUID>();
        fakeEmail.respond((recipient, text) -> {
            if (email.equals(recipient.email())) {
                jdbc.sql("update notifications.delivery set next_attempt_at = ? where message_id = ?")
                        .params(Timestamp.from(moved), messageId.get())
                        .update();
            }
            return new SendResult.Sent();
        });
        var booking = bookByMaster(client("Анна", null, email), slot(MONDAY, "14:00"), false);
        messageId.set(messageId(booking.id(), MessageKind.BOOKED_BY_MASTER));

        dispatcher.run();

        assertThat(delivery(messageId.get(), Channel.EMAIL))
                .extracting(Delivery::status, Delivery::attempts, Delivery::nextAttemptAt, Delivery::sentAt)
                .containsExactly(DeliveryStatus.PENDING, 1, moved, null);
    }

    /** Процесс упал посреди отправки: исход не записан, по истечении аренды доставка уходит снова — попытка вторая. */
    @Test
    void deliveryLeftByCrashIsSentAgainAfterLease() {
        var email = email();
        var calls = new AtomicInteger();
        fakeEmail.respond((recipient, text) -> {
            if (email.equals(recipient.email()) && calls.getAndIncrement() == 0) {
                throw new AssertionError("process crashed while sending");
            }
            return new SendResult.Sent();
        });
        var booking = bookByMaster(client("Анна", null, email), slot(MONDAY, "14:00"), false);
        var messageId = messageId(booking.id(), MessageKind.BOOKED_BY_MASTER);

        assertThatThrownBy(dispatcher::run).isInstanceOf(AssertionError.class);

        assertThat(delivery(messageId, Channel.EMAIL))
                .extracting(Delivery::status, Delivery::attempts, Delivery::nextAttemptAt)
                .containsExactly(DeliveryStatus.PENDING, 1, clock.instant().plus(Duration.ofMinutes(5)));

        clock.advance(Duration.ofMinutes(4));
        dispatcher.run();
        assertThat(fakeEmail.toEmail(email)).isEmpty();

        clock.advance(Duration.ofMinutes(1));
        dispatcher.run();

        assertThat(delivery(messageId, Channel.EMAIL))
                .extracting(Delivery::status, Delivery::attempts)
                .containsExactly(DeliveryStatus.SENT, 2);
        assertThat(fakeEmail.toEmail(email)).hasSize(1);
    }

    /** Пятая попытка пропала вместе с процессом: по истечении аренды доставка неудачна, канал не вызывается. */
    @Test
    void deliveryWithoutAttemptsLeftFailsAtClaim() {
        var email = email();
        fakeEmail.respond((recipient, text) -> {
            if (email.equals(recipient.email())) {
                throw new AssertionError("process crashed while sending");
            }
            return new SendResult.Sent();
        });
        var booking = bookByMaster(client("Анна", null, email), slot(MONDAY, "14:00"), false);
        var messageId = messageId(booking.id(), MessageKind.BOOKED_BY_MASTER);
        assertThatThrownBy(dispatcher::run).isInstanceOf(AssertionError.class);
        jdbc.sql("update notifications.delivery set attempts = 5 where message_id = ?")
                .param(messageId)
                .update();
        var calls = new AtomicInteger();
        fakeEmail.respond((recipient, text) -> {
            if (email.equals(recipient.email())) {
                calls.incrementAndGet();
            }
            return new SendResult.Sent();
        });

        clock.advance(Duration.ofMinutes(5));
        dispatcher.run();

        assertThat(delivery(messageId, Channel.EMAIL))
                .extracting(Delivery::status, Delivery::attempts, Delivery::errorCode)
                .containsExactly(DeliveryStatus.FAILED, 5, "transient_exhausted");
        assertThat(calls).hasValue(0);
    }

    /** Триггер теста отвергает вставку доставок одного сообщения; снимается после теста. */
    private void failDeliveryInsertsOf(UUID messageId) {
        var trigger = "dispatcher_test_" + messageId.toString().replace("-", "");
        failureTrigger = trigger;
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION notifications.%s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.message_id = '%s' THEN
                        RAISE EXCEPTION 'delivery rejected by test';
                    END IF;
                    RETURN NEW;
                END $$""".formatted(trigger, messageId)).update();
        jdbc.sql("""
                CREATE OR REPLACE TRIGGER %1$s BEFORE INSERT ON notifications.delivery
                FOR EACH ROW EXECUTE FUNCTION notifications.%1$s()""".formatted(trigger)).update();
    }

    @Test
    void dispatchRunsOutsideTransactionOnly() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> dispatcher.run()))
                .isInstanceOf(InternalException.class);
    }

    private UUID insertMessage(
            MessageKind kind, UUID clientId, UUID bookingId, Instant dueAt, Instant expiresAt, Instant occurredAt) {
        // UUID v7: строки, вставленные позже, — с большим id, как в очереди.
        var id = UuidV7.next();
        jdbc.sql("""
                        insert into notifications.message (id, client_id, kind, booking_id, due_at, expires_at, status,
                            dedup_key, occurred_at, created_at)
                        values (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)
                        """)
                .params(
                        id,
                        clientId,
                        kind.name(),
                        bookingId,
                        Timestamp.from(dueAt),
                        Timestamp.from(expiresAt),
                        "test:" + id,
                        Timestamp.from(occurredAt),
                        Timestamp.from(clock.instant()))
                .update();
        return id;
    }

    private UUID messageId(UUID bookingId, MessageKind kind) {
        return jdbc.sql("select id from notifications.message where booking_id = ? and kind = ?")
                .params(bookingId, kind.name())
                .query(UUID.class)
                .single();
    }

    private UUID staffMessageId(UUID bookingId, UUID accountId) {
        return jdbc.sql("select id from notifications.message where booking_id = ? and account_id = ?")
                .params(bookingId, accountId)
                .query(UUID.class)
                .single();
    }

    private MessageStatus status(UUID messageId) {
        return MessageStatus.valueOf(jdbc.sql("select status from notifications.message where id = ?")
                .param(messageId)
                .query(String.class)
                .single());
    }

    /** Статусы предложений прийти раньше по записи, в порядке событий. */
    private List<MessageStatus> earlierProposals(UUID bookingId) {
        return jdbc.sql("""
                        select status from notifications.message
                        where booking_id = ? and kind = 'EARLIER_PROPOSED' order by occurred_at
                        """).param(bookingId).query(String.class).list().stream()
                .map(MessageStatus::valueOf)
                .toList();
    }

    private List<Delivery> deliveries(UUID messageId) {
        return jdbc.sql("""
                        select channel, status, attempts, next_attempt_at, error_code, sent_at
                        from notifications.delivery where message_id = ? order by channel
                        """).param(messageId).query(DispatcherTest::delivery).list();
    }

    private Delivery delivery(UUID messageId, Channel channel) {
        return deliveries(messageId).stream()
                .filter(delivery -> delivery.channel() == channel)
                .findFirst()
                .orElseThrow();
    }

    private @Nullable Long telegramChat(UUID accountId) {
        return jdbc.sql("select chat_id from notifications.telegram_chat where account_id = ?")
                .param(accountId)
                .query(Long.class)
                .optional()
                .orElse(null);
    }

    private static Delivery delivery(ResultSet row, int rowNumber) throws SQLException {
        var sentAt = row.getTimestamp("sent_at");
        return new Delivery(
                Channel.valueOf(row.getString("channel")),
                DeliveryStatus.valueOf(row.getString("status")),
                row.getInt("attempts"),
                row.getTimestamp("next_attempt_at").toInstant(),
                row.getString("error_code"),
                sentAt == null ? null : sentAt.toInstant());
    }

    private static String email() {
        return "client-" + UUID.randomUUID() + "@example.com";
    }

    private static long chat() {
        return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }

    /** Доставка, как её видит тест. */
    record Delivery(
            Channel channel,
            DeliveryStatus status,
            int attempts,
            Instant nextAttemptAt,
            @Nullable String errorCode,
            @Nullable Instant sentAt) {}

    /** Подменные каналы почты и Telegram — вместо настоящих (Tasks 7, 8). */
    @TestConfiguration(proxyBeanMethods = false)
    static class Channels {

        @Bean
        FakeChannel fakeEmail() {
            return new FakeChannel(Channel.EMAIL);
        }

        @Bean
        FakeChannel fakeTelegram() {
            return new FakeChannel(Channel.TELEGRAM);
        }
    }
}
