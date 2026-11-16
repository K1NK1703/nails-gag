package gag.nails.notifications.internal.text;

import gag.nails.booking.BookingSettingsService;
import gag.nails.booking.model.BookedServiceView;
import gag.nails.booking.model.BookingStatus;
import gag.nails.booking.model.BookingView;
import gag.nails.booking.model.CancelReason;
import gag.nails.clients.model.ClientView;
import gag.nails.common.time.SalonTimeZone;
import gag.nails.notifications.internal.NotificationsProperties;
import gag.nails.notifications.model.MessageKind;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Тексты сообщений (спека §5.3): по шаблону на вид, простой текст по-русски, на «вы». Первая строка — тема письма;
 * Telegram получает весь текст. Подставляются текущие данные записи и карточки клиентки на момент отправки: дата и
 * время визита по времени салона («сб, 21 ноября, 14:00»), услуги, адрес салона, ссылки на кабинет
 * ({@code <site-url>/account}) и на запись в админке ({@code <site-url>/admin/bookings/<id>}). Клиентке — по имени,
 * сотрудникам — имя и фамилия клиентки.
 *
 * <p>Денег сайт не принимает: в текстах нет реквизитов, номеров карт и ссылок на оплату. О предоплате — только сумма и
 * срок из записи и подсказка, у кого спросить, как её внести.
 */
@Component
public class MessageTexts {

    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");

    private final NotificationsProperties properties;
    private final BookingSettingsService bookingSettings;
    private final DateTimeFormatter dateTime;
    private final DateTimeFormatter time;

    public MessageTexts(
            NotificationsProperties properties, SalonTimeZone salonTimeZone, BookingSettingsService bookingSettings) {
        this.properties = properties;
        this.bookingSettings = bookingSettings;
        this.dateTime =
                DateTimeFormatter.ofPattern("EEE, d MMMM, HH:mm", RUSSIAN).withZone(salonTimeZone.zone());
        this.time = DateTimeFormatter.ofPattern("HH:mm", RUSSIAN).withZone(salonTimeZone.zone());
    }

    /**
     * Текст сообщения вида {@code kind} о записи {@code booking}.
     *
     * @param client текущая карточка клиентки записи: получателя клиентских сообщений и героини мастерских
     */
    public String render(MessageKind kind, BookingView booking, ClientView client) {
        var lines = switch (kind) {
            case BOOKING_RECEIVED ->
                List.of(
                        "Вы записаны на " + visit(booking),
                        client.firstName() + ", спасибо за запись! Ждём вас.",
                        services(booking),
                        address(),
                        "Перенести или отменить запись можно в личном кабинете: " + account());
            case BOOKED_BY_MASTER ->
                List.of(
                        "Вы записаны на " + visit(booking),
                        client.firstName() + ", мастер записал вас на визит. Ждём вас!",
                        services(booking),
                        address(),
                        "Ваши записи — в личном кабинете: " + account());
            case PREPAYMENT_REQUESTED ->
                List.of(
                        "Запись на " + visit(booking) + " ждёт предоплату",
                        client.firstName() + ", чтобы подтвердить запись, внесите предоплату " + prepayment(booking)
                                + ". Если предоплаты к этому сроку не будет, запись отменится.",
                        "Как внести предоплату — уточните у мастера: " + properties.masterTelegram(),
                        services(booking),
                        address(),
                        "Личный кабинет: " + account());
            case CONFIRMED ->
                List.of(
                        "Запись подтверждена: " + visit(booking),
                        client.firstName() + ", ждём вас!",
                        services(booking),
                        address(),
                        "Перенести или отменить запись можно в личном кабинете: " + account());
            case CANCELLED_BY_CLIENT ->
                List.of(
                        "Запись на " + visit(booking) + " отменена",
                        client.firstName() + ", вы отменили запись. Будем рады видеть вас снова!",
                        "Записаться на другое время можно в личном кабинете: " + account());
            case CANCELLED ->
                List.of(
                        "Запись на " + visit(booking) + " отменена",
                        client.firstName() + ", " + cancellation(booking.cancelReason()),
                        "Записаться на другое время можно в личном кабинете: " + account(),
                        "Написать мастеру: " + properties.masterTelegram());
            case RESCHEDULED ->
                List.of(
                        "Запись перенесена на " + visit(booking),
                        client.firstName() + ", ждём вас в новое время.",
                        services(booking),
                        address(),
                        "Ваши записи — в личном кабинете: " + account());
            case EARLIER_PROPOSED ->
                List.of(
                        "Можно прийти раньше: " + dateTime.format(proposedStart(booking)),
                        client.firstName() + ", мастер предлагает начать визит раньше: в "
                                + time.format(proposedStart(booking)) + " вместо " + time.format(booking.visitStart())
                                + ".",
                        "Принять или отклонить предложение можно в личном кабинете: " + account());
            case REMINDER_DAY_BEFORE ->
                List.of(
                        "Напоминаем о визите: " + visit(booking),
                        client.firstName() + ", ждём вас!",
                        services(booking),
                        address(),
                        "Если планы изменились, перенесите или отмените запись в личном кабинете: " + account());
            case REMINDER_HOURS_BEFORE ->
                List.of(
                        "Скоро визит: " + visit(booking),
                        client.firstName() + ", ждём вас в " + time.format(booking.visitStart()) + ".",
                        address(),
                        "Если опаздываете, напишите мастеру: " + properties.masterTelegram());
            case STAFF_NEW_BOOKING ->
                staff(
                        "Новая запись: " + visit(booking),
                        booking,
                        client,
                        booking.status() == BookingStatus.AWAITING_PREPAYMENT && booking.prepaymentDeadline() != null
                                ? "Ждёт предоплату до " + dateTime.format(booking.prepaymentDeadline()) + "."
                                : null);
            case STAFF_CLIENT_CANCELLED ->
                staff(
                        "Клиентка отменила запись: " + visit(booking),
                        booking,
                        client,
                        booking.cancelReason() == CancelReason.CLIENT_LATE
                                ? "Отмена позже окна бесплатной отмены: следующая запись — по предоплате."
                                : null);
            case STAFF_PREPAYMENT_TIMEOUT ->
                staff("Нет предоплаты, запись отменена: " + visit(booking), booking, client, null);
            case STAFF_CLIENT_RESCHEDULED ->
                staff("Клиентка перенесла запись на " + visit(booking), booking, client, null);
            case STAFF_EARLIER_ACCEPTED ->
                staff(
                        "Клиентка придёт раньше: " + visit(booking),
                        booking,
                        client,
                        "Согласилась начать визит в " + time.format(booking.visitStart()) + ".");
            case STAFF_EARLIER_DECLINED ->
                staff(
                        "Клиентка не сможет прийти раньше: " + visit(booking),
                        booking,
                        client,
                        "Отказалась от предложения, визит — в прежнее время.");
        };
        return String.join("\n", lines);
    }

    /** Мастерское сообщение: заголовок, клиентка, услуги, пояснение (если есть) и ссылка на запись в админке. */
    private List<String> staff(String headline, BookingView booking, ClientView client, @Nullable String note) {
        var lines = new ArrayList<String>();
        lines.add(headline);
        lines.add("Клиентка: " + fullName(client));
        lines.add(services(booking));
        if (note != null) {
            lines.add(note);
        }
        lines.add("Запись: " + properties.siteUrl() + "/admin/bookings/" + booking.id());
        return lines;
    }

    private String visit(BookingView booking) {
        return dateTime.format(booking.visitStart());
    }

    /** Предложенное время; предложения уже нет (не должно случиться: актуальность проверена) — начало визита. */
    private static Instant proposedStart(BookingView booking) {
        var offer = booking.earlierTime();
        return offer != null ? offer.proposedStart() : booking.visitStart();
    }

    /** Сумма и срок: «600 ₽ до ср, 19 ноября, 18:00». Сумма — по правилам записи от прайса выбранного состава. */
    private String prepayment(BookingView booking) {
        var planned = booking.plannedServices().stream()
                .map(BookedServiceView::price)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var amount = bookingSettings.settings().defaultPrepaymentAmount(planned);
        var deadline = booking.prepaymentDeadline();
        return rubles(amount) + (deadline != null ? " до " + dateTime.format(deadline) : "");
    }

    private static String cancellation(@Nullable CancelReason reason) {
        if (reason == null) {
            return "запись отменена.";
        }
        return switch (reason) {
            case CLIENT, CLIENT_LATE -> "вы отменили запись.";
            case MASTER -> "к сожалению, мастер отменил запись. Приносим извинения.";
            case LATE_ARRIVAL -> "запись отменена из-за опоздания.";
            case PREPAYMENT_TIMEOUT -> "запись отменена: предоплата не поступила в срок.";
            case ACCOUNT_DELETED -> "запись отменена, потому что вы удалили аккаунт.";
        };
    }

    private static String services(BookingView booking) {
        return "Услуги: "
                + booking.plannedServices().stream()
                        .map(BookedServiceView::title)
                        .collect(Collectors.joining(", "));
    }

    private String address() {
        return "Адрес: " + properties.salonAddress();
    }

    private String account() {
        return properties.siteUrl() + "/account";
    }

    private static String fullName(ClientView client) {
        var lastName = client.lastName();
        return lastName == null || lastName.isBlank() ? client.firstName() : client.firstName() + " " + lastName;
    }

    /** Рубли без копеек, если их нет («600 ₽»), иначе с двумя знаками через запятую («600,50 ₽»). */
    private static String rubles(BigDecimal amount) {
        var whole = amount.stripTrailingZeros().scale() <= 0;
        var text = whole
                ? amount.setScale(0, RoundingMode.UNNECESSARY).toPlainString()
                : amount.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
        return text + " ₽";
    }
}
