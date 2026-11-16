package gag.nails.notifications.internal.text;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.booking.BookingSettingsService;
import gag.nails.booking.command.UpdateBookingSettings;
import gag.nails.booking.model.BookedServiceView;
import gag.nails.booking.model.BookingCreator;
import gag.nails.booking.model.BookingSettingsChangeView;
import gag.nails.booking.model.BookingSettingsView;
import gag.nails.booking.model.BookingStatus;
import gag.nails.booking.model.BookingView;
import gag.nails.booking.model.CancelReason;
import gag.nails.booking.model.EarlierTimeStatus;
import gag.nails.booking.model.EarlierTimeView;
import gag.nails.catalog.model.ServiceKind;
import gag.nails.clients.model.ClientSource;
import gag.nails.clients.model.ClientView;
import gag.nails.common.actor.Actor;
import gag.nails.common.time.SalonTimeZone;
import gag.nails.notifications.internal.NotificationsProperties;
import gag.nails.notifications.internal.NotificationsProperties.Email;
import gag.nails.notifications.internal.NotificationsProperties.Jobs;
import gag.nails.notifications.internal.NotificationsProperties.QuietHoursWindow;
import gag.nails.notifications.internal.NotificationsProperties.Telegram;
import gag.nails.notifications.model.MessageKind;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Тексты сообщений — точное совпадение с эталоном на каждый вид (спека §5.3). Визит — суббота 21 ноября 2026, 14:00
 * по Москве; услуги — маникюр (2000 ₽) и дизайн (500 ₽), предоплата 30 % — 750 ₽.
 */
class MessageTextsTest {

    private static final UUID BOOKING = UUID.fromString("019a8600-0000-7000-8000-0000000000b1");
    private static final Instant VISIT = Instant.parse("2026-11-21T11:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-11-21T09:30:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-11-19T15:00:00Z");
    private static final String ADMIN = "https://nails-gag.ru/admin/bookings/019a8600-0000-7000-8000-0000000000b1";

    private final MessageTexts texts =
            new MessageTexts(properties(), new SalonTimeZone(ZoneId.of("Europe/Moscow")), new FixedBookingSettings(30));

    private final ClientView anna = client("Анна", "Иванова");

    @Test
    void bookingReceived() {
        assertThat(texts.render(MessageKind.BOOKING_RECEIVED, booking(BookingStatus.CONFIRMED), anna))
                .isEqualTo("""
                        Вы записаны на сб, 21 ноября, 14:00
                        Анна, спасибо за запись! Ждём вас.
                        Услуги: Маникюр, Дизайн
                        Адрес: пр. КИМа 6, метро Приморская, 3 этаж, студия 365
                        Перенести или отменить запись можно в личном кабинете: https://nails-gag.ru/account""");
    }

    @Test
    void bookedByMaster() {
        assertThat(texts.render(MessageKind.BOOKED_BY_MASTER, booking(BookingStatus.CONFIRMED), anna))
                .isEqualTo("""
                        Вы записаны на сб, 21 ноября, 14:00
                        Анна, мастер записал вас на визит. Ждём вас!
                        Услуги: Маникюр, Дизайн
                        Адрес: пр. КИМа 6, метро Приморская, 3 этаж, студия 365
                        Ваши записи — в личном кабинете: https://nails-gag.ru/account""");
    }

    @Test
    void prepaymentRequested() {
        assertThat(texts.render(MessageKind.PREPAYMENT_REQUESTED, awaitingPrepayment(), anna))
                .isEqualTo("""
                        Запись на сб, 21 ноября, 14:00 ждёт предоплату
                        Анна, чтобы подтвердить запись, внесите предоплату 750 ₽ до чт, 19 ноября, 18:00. \
                        Если предоплаты к этому сроку не будет, запись отменится.
                        Как внести предоплату — уточните у мастера: @gagushichewa
                        Услуги: Маникюр, Дизайн
                        Адрес: пр. КИМа 6, метро Приморская, 3 этаж, студия 365
                        Личный кабинет: https://nails-gag.ru/account""");
    }

    @Test
    void prepaymentAmountFollowsBookingRules() {
        var texts = new MessageTexts(
                properties(), new SalonTimeZone(ZoneId.of("Europe/Moscow")), new FixedBookingSettings(33));

        // 33 % от 2500 ₽ — 825 ₽: процент от прайса выбранного состава, округлённый до рубля.
        assertThat(texts.render(MessageKind.PREPAYMENT_REQUESTED, awaitingPrepayment(), anna))
                .contains("внесите предоплату 825 ₽ до чт, 19 ноября, 18:00.");
    }

    @Test
    void confirmed() {
        assertThat(texts.render(MessageKind.CONFIRMED, booking(BookingStatus.CONFIRMED), anna))
                .isEqualTo("""
                        Запись подтверждена: сб, 21 ноября, 14:00
                        Анна, ждём вас!
                        Услуги: Маникюр, Дизайн
                        Адрес: пр. КИМа 6, метро Приморская, 3 этаж, студия 365
                        Перенести или отменить запись можно в личном кабинете: https://nails-gag.ru/account""");
    }

    @Test
    void cancelledByClient() {
        assertThat(texts.render(MessageKind.CANCELLED_BY_CLIENT, cancelled(CancelReason.CLIENT), anna))
                .isEqualTo("""
                        Запись на сб, 21 ноября, 14:00 отменена
                        Анна, вы отменили запись. Будем рады видеть вас снова!
                        Записаться на другое время можно в личном кабинете: https://nails-gag.ru/account""");
    }

    @Test
    void cancelledByMaster() {
        assertThat(texts.render(MessageKind.CANCELLED, cancelled(CancelReason.MASTER), anna))
                .isEqualTo("""
                        Запись на сб, 21 ноября, 14:00 отменена
                        Анна, к сожалению, мастер отменил запись. Приносим извинения.
                        Записаться на другое время можно в личном кабинете: https://nails-gag.ru/account
                        Написать мастеру: @gagushichewa""");
    }

    @Test
    void cancelledNamesItsReason() {
        assertThat(texts.render(MessageKind.CANCELLED, cancelled(CancelReason.LATE_ARRIVAL), anna))
                .contains("\nАнна, запись отменена из-за опоздания.\n");
        assertThat(texts.render(MessageKind.CANCELLED, cancelled(CancelReason.PREPAYMENT_TIMEOUT), anna))
                .contains("\nАнна, запись отменена: предоплата не поступила в срок.\n");
        assertThat(texts.render(MessageKind.CANCELLED, cancelled(CancelReason.ACCOUNT_DELETED), anna))
                .contains("\nАнна, запись отменена, потому что вы удалили аккаунт.\n");
        assertThat(texts.render(MessageKind.CANCELLED, cancelled(CancelReason.CLIENT_LATE), anna))
                .contains("\nАнна, вы отменили запись.\n");
    }

    @Test
    void rescheduled() {
        assertThat(texts.render(MessageKind.RESCHEDULED, booking(BookingStatus.CONFIRMED), anna))
                .isEqualTo("""
                        Запись перенесена на сб, 21 ноября, 14:00
                        Анна, ждём вас в новое время.
                        Услуги: Маникюр, Дизайн
                        Адрес: пр. КИМа 6, метро Приморская, 3 этаж, студия 365
                        Ваши записи — в личном кабинете: https://nails-gag.ru/account""");
    }

    @Test
    void earlierProposed() {
        var booking = with(
                booking(BookingStatus.CONFIRMED),
                new EarlierTimeView(EARLIER, VISIT.minus(Duration.ofDays(2)), EarlierTimeStatus.PENDING),
                null);

        assertThat(texts.render(MessageKind.EARLIER_PROPOSED, booking, anna)).isEqualTo("""
                        Можно прийти раньше: сб, 21 ноября, 12:30
                        Анна, мастер предлагает начать визит раньше: в 12:30 вместо 14:00.
                        Принять или отклонить предложение можно в личном кабинете: https://nails-gag.ru/account""");
    }

    @Test
    void reminderDayBefore() {
        assertThat(texts.render(MessageKind.REMINDER_DAY_BEFORE, booking(BookingStatus.CONFIRMED), anna))
                .isEqualTo("""
                        Напоминаем о визите: сб, 21 ноября, 14:00
                        Анна, ждём вас!
                        Услуги: Маникюр, Дизайн
                        Адрес: пр. КИМа 6, метро Приморская, 3 этаж, студия 365
                        Если планы изменились, перенесите или отмените запись в личном кабинете: \
                        https://nails-gag.ru/account""");
    }

    @Test
    void reminderHoursBefore() {
        assertThat(texts.render(MessageKind.REMINDER_HOURS_BEFORE, booking(BookingStatus.CONFIRMED), anna))
                .isEqualTo("""
                        Скоро визит: сб, 21 ноября, 14:00
                        Анна, ждём вас в 14:00.
                        Адрес: пр. КИМа 6, метро Приморская, 3 этаж, студия 365
                        Если опаздываете, напишите мастеру: @gagushichewa""");
    }

    @Test
    void remindersFollowAgreedEarlierStart() {
        var booking = with(
                booking(BookingStatus.CONFIRMED),
                new EarlierTimeView(EARLIER, VISIT.minus(Duration.ofDays(2)), EarlierTimeStatus.ACCEPTED),
                EARLIER);

        assertThat(texts.render(MessageKind.REMINDER_HOURS_BEFORE, booking, anna))
                .startsWith("Скоро визит: сб, 21 ноября, 12:30\nАнна, ждём вас в 12:30.\n");
    }

    @Test
    void staffNewBooking() {
        assertThat(texts.render(MessageKind.STAFF_NEW_BOOKING, booking(BookingStatus.CONFIRMED), anna))
                .isEqualTo("""
                        Новая запись: сб, 21 ноября, 14:00
                        Клиентка: Анна Иванова
                        Услуги: Маникюр, Дизайн
                        Запись: %s""".formatted(ADMIN));
    }

    @Test
    void staffNewBookingAwaitingPrepayment() {
        assertThat(texts.render(MessageKind.STAFF_NEW_BOOKING, awaitingPrepayment(), anna))
                .isEqualTo("""
                        Новая запись: сб, 21 ноября, 14:00
                        Клиентка: Анна Иванова
                        Услуги: Маникюр, Дизайн
                        Ждёт предоплату до чт, 19 ноября, 18:00.
                        Запись: %s""".formatted(ADMIN));
    }

    @Test
    void staffClientCancelled() {
        assertThat(texts.render(MessageKind.STAFF_CLIENT_CANCELLED, cancelled(CancelReason.CLIENT), anna))
                .isEqualTo("""
                        Клиентка отменила запись: сб, 21 ноября, 14:00
                        Клиентка: Анна Иванова
                        Услуги: Маникюр, Дизайн
                        Запись: %s""".formatted(ADMIN));
    }

    @Test
    void staffClientCancelledLate() {
        assertThat(texts.render(MessageKind.STAFF_CLIENT_CANCELLED, cancelled(CancelReason.CLIENT_LATE), anna))
                .isEqualTo("""
                        Клиентка отменила запись: сб, 21 ноября, 14:00
                        Клиентка: Анна Иванова
                        Услуги: Маникюр, Дизайн
                        Отмена позже окна бесплатной отмены: следующая запись — по предоплате.
                        Запись: %s""".formatted(ADMIN));
    }

    @Test
    void staffPrepaymentTimeout() {
        assertThat(texts.render(MessageKind.STAFF_PREPAYMENT_TIMEOUT, cancelled(CancelReason.PREPAYMENT_TIMEOUT), anna))
                .isEqualTo("""
                        Нет предоплаты, запись отменена: сб, 21 ноября, 14:00
                        Клиентка: Анна Иванова
                        Услуги: Маникюр, Дизайн
                        Запись: %s""".formatted(ADMIN));
    }

    @Test
    void staffClientRescheduled() {
        assertThat(texts.render(MessageKind.STAFF_CLIENT_RESCHEDULED, booking(BookingStatus.CONFIRMED), anna))
                .isEqualTo("""
                        Клиентка перенесла запись на сб, 21 ноября, 14:00
                        Клиентка: Анна Иванова
                        Услуги: Маникюр, Дизайн
                        Запись: %s""".formatted(ADMIN));
    }

    @Test
    void staffEarlierAccepted() {
        var booking = with(
                booking(BookingStatus.CONFIRMED),
                new EarlierTimeView(EARLIER, VISIT.minus(Duration.ofDays(2)), EarlierTimeStatus.ACCEPTED),
                EARLIER);

        assertThat(texts.render(MessageKind.STAFF_EARLIER_ACCEPTED, booking, anna))
                .isEqualTo("""
                        Клиентка придёт раньше: сб, 21 ноября, 12:30
                        Клиентка: Анна Иванова
                        Услуги: Маникюр, Дизайн
                        Согласилась начать визит в 12:30.
                        Запись: %s""".formatted(ADMIN));
    }

    @Test
    void staffEarlierDeclined() {
        var booking = with(
                booking(BookingStatus.CONFIRMED),
                new EarlierTimeView(EARLIER, VISIT.minus(Duration.ofDays(2)), EarlierTimeStatus.DECLINED),
                null);

        assertThat(texts.render(MessageKind.STAFF_EARLIER_DECLINED, booking, anna))
                .isEqualTo("""
                        Клиентка не сможет прийти раньше: сб, 21 ноября, 14:00
                        Клиентка: Анна Иванова
                        Услуги: Маникюр, Дизайн
                        Отказалась от предложения, визит — в прежнее время.
                        Запись: %s""".formatted(ADMIN));
    }

    @Test
    void staffTextOfClientWithoutLastNameNamesHerByFirstName() {
        assertThat(texts.render(MessageKind.STAFF_NEW_BOOKING, booking(BookingStatus.CONFIRMED), client("Анна", null)))
                .contains("\nКлиентка: Анна\n");
    }

    /** Денег сайт не принимает: ни в одном тексте нет реквизитов, карт и ссылок на оплату; ссылки — только на сайт. */
    @ParameterizedTest
    @EnumSource(MessageKind.class)
    void noTextMentionsPaymentDetails(MessageKind kind) {
        for (var text : variants(kind)) {
            var lower = text.toLowerCase();
            assertThat(lower).doesNotContain("карт").doesNotContain("реквизит").doesNotContain("оплатить по ссылке");
            for (var link : text.split("\\s+")) {
                if (link.startsWith("http")) {
                    assertThat(link).startsWith("https://nails-gag.ru/");
                }
            }
        }
    }

    /** Первая строка каждого текста — короткая тема письма. */
    @ParameterizedTest
    @EnumSource(MessageKind.class)
    void everyTextStartsWithItsSubject(MessageKind kind) {
        for (var text : variants(kind)) {
            var subject = text.lines().findFirst().orElseThrow();
            assertThat(subject).isNotBlank().hasSizeLessThanOrEqualTo(80);
            assertThat(text.lines().count()).isGreaterThan(1);
        }
    }

    /** Текст вида во всех состояниях записи, в которых он может уйти. */
    private List<String> variants(MessageKind kind) {
        var bookings = new ArrayList<BookingView>();
        bookings.add(booking(BookingStatus.CONFIRMED));
        bookings.add(awaitingPrepayment());
        for (var reason : CancelReason.values()) {
            bookings.add(cancelled(reason));
        }
        bookings.add(with(
                booking(BookingStatus.CONFIRMED),
                new EarlierTimeView(EARLIER, VISIT.minus(Duration.ofDays(2)), EarlierTimeStatus.PENDING),
                null));
        return bookings.stream()
                .map(booking -> texts.render(kind, booking, anna))
                .toList();
    }

    private static BookingView booking(BookingStatus status) {
        return new BookingView(
                BOOKING,
                UUID.fromString("019a8600-0000-7000-8000-0000000000c1"),
                UUID.fromString("019a8600-0000-7000-8000-0000000000d1"),
                VISIT,
                VISIT.plus(Duration.ofHours(2)),
                status,
                BookingCreator.CLIENT,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(
                        new BookedServiceView(
                                UUID.randomUUID(), ServiceKind.MAIN, "Маникюр", new BigDecimal("2000.00")),
                        new BookedServiceView(
                                UUID.randomUUID(), ServiceKind.ADDON, "Дизайн", new BigDecimal("500.00"))),
                List.of(),
                null,
                null,
                null,
                null,
                null);
    }

    private static BookingView awaitingPrepayment() {
        var booking = booking(BookingStatus.AWAITING_PREPAYMENT);
        return new BookingView(
                booking.id(),
                booking.clientId(),
                booking.slotId(),
                booking.slotStartsAt(),
                booking.slotEndsAt(),
                booking.status(),
                booking.createdBy(),
                null,
                true,
                DEADLINE,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                booking.plannedServices(),
                List.of(),
                null,
                null,
                null,
                null,
                null);
    }

    private static BookingView cancelled(CancelReason reason) {
        var booking = booking(BookingStatus.CANCELLED);
        return new BookingView(
                booking.id(),
                booking.clientId(),
                booking.slotId(),
                booking.slotStartsAt(),
                booking.slotEndsAt(),
                booking.status(),
                booking.createdBy(),
                null,
                false,
                null,
                null,
                null,
                null,
                reason,
                VISIT.minus(Duration.ofDays(1)),
                null,
                null,
                booking.plannedServices(),
                List.of(),
                null,
                null,
                null,
                null,
                null);
    }

    private static BookingView with(BookingView booking, EarlierTimeView earlierTime, @Nullable Instant agreedStart) {
        return new BookingView(
                booking.id(),
                booking.clientId(),
                booking.slotId(),
                booking.slotStartsAt(),
                booking.slotEndsAt(),
                booking.status(),
                booking.createdBy(),
                null,
                false,
                null,
                null,
                earlierTime,
                agreedStart,
                null,
                null,
                null,
                null,
                booking.plannedServices(),
                List.of(),
                null,
                null,
                null,
                null,
                null);
    }

    private static ClientView client(String firstName, @Nullable String lastName) {
        return new ClientView(
                UUID.fromString("019a8600-0000-7000-8000-0000000000c1"),
                firstName,
                lastName,
                null,
                null,
                "anna@example.com",
                null,
                ClientSource.ADDED_BY_MASTER,
                null,
                false,
                null,
                false,
                false);
    }

    private static NotificationsProperties properties() {
        return new NotificationsProperties(
                new Jobs(false, Duration.ofMinutes(1), "0 15 4 * * *"),
                90,
                new QuietHoursWindow(LocalTime.of(22, 0), LocalTime.of(9, 0)),
                "https://nails-gag.ru",
                "@gagushichewa",
                "пр. КИМа 6, метро Приморская, 3 этаж, студия 365",
                new Email(""),
                new Telegram("", "", Duration.ofSeconds(50)));
    }

    /** Правила записи с заданным процентом предоплаты; остальное тексты не читают. */
    private record FixedBookingSettings(int prepaymentPercent) implements BookingSettingsService {

        @Override
        public BookingSettingsView settings() {
            return new BookingSettingsView(3, 60, 24, 48, prepaymentPercent, 15, 7);
        }

        @Override
        public BookingSettingsView update(UpdateBookingSettings command, Actor actor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<BookingSettingsChangeView> history(Actor actor) {
            throw new UnsupportedOperationException();
        }
    }
}
