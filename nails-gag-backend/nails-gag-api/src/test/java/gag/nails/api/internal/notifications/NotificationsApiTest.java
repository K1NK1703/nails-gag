package gag.nails.api.internal.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Section;
import gag.nails.common.paging.Paging;
import gag.nails.identity.testing.FakeProfile;
import gag.nails.notifications.NotificationPreferences;
import gag.nails.notifications.NotificationSettings;
import gag.nails.notifications.command.DeliveryFilter;
import gag.nails.notifications.command.UpdateReminderSettings;
import gag.nails.notifications.model.DeliveryStatus;
import gag.nails.notifications.model.DeliveryView;
import gag.nails.notifications.model.ReminderSettings;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient.RequestHeadersSpec;
import tools.jackson.databind.JsonNode;

/**
 * Уведомления по HTTP (спецификация уведомлений, §4): свои каналы и привязка Telegram — любой сессии; настройки
 * напоминаний и журналы — по разделу «Уведомления», его проверяет модуль. Здесь — что адреса доходят до нужного вызова,
 * отдают его ответ в именах тела HTTP и показывают отказы по правилам спецификации API (§4.5–4.6). Бот в этом контексте
 * не настроен; ссылку привязки при настроенном боте проверяет {@link TelegramLinkApiTest}.
 *
 * <p>Настройки напоминаний в базе одни на всех: тест, который их меняет, возвращает прежние значения после себя.
 * Доставки журнала тест кладёт строками в базу по своей записи (её id нигде больше не встречается) и убирает за собой.
 */
@ApiTest
class NotificationsApiTest extends ApiTestSupport {

    private static final String ME = "/api/notifications/me";
    private static final String TELEGRAM_LINK = "/api/notifications/me/telegram-link";
    private static final String TELEGRAM = "/api/notifications/me/telegram";
    private static final String SETTINGS = "/api/notifications/settings";
    private static final String HISTORY = "/api/notifications/settings/history";
    private static final String DELIVERIES = "/api/notifications/deliveries";

    @Autowired
    NotificationSettings settings;

    @Autowired
    NotificationPreferences preferences;

    @Autowired
    JdbcClient jdbc;

    /** Настройки до теста, если тест их меняет; после теста они возвращаются. */
    private @Nullable ReminderSettings originalSettings;

    /** Записи, по которым тест положил сообщения и доставки. */
    private final List<UUID> ownBookings = new ArrayList<>();

    @AfterEach
    void restore() {
        var original = originalSettings;
        if (original != null) {
            settings.update(
                    new UpdateReminderSettings(original.dayBeforeAt(), original.hoursBefore()), developerActor());
        }
        ownBookings.forEach(booking -> jdbc.sql("delete from notifications.message where booking_id = ?")
                .param(booking)
                .update());
    }

    @Test
    void clientReadsAndChangesOwnChannels() {
        var anna = signInClientWithEmail();

        var mine = noStore(get(anna, ME));
        assertThat(field(mine, "emailEnabled").asBoolean()).isTrue();
        assertThat(field(mine, "emailAvailable").asBoolean()).isTrue();
        assertThat(field(mine, "telegramEnabled").asBoolean()).isTrue();
        assertThat(field(mine, "telegramLinked").asBoolean()).isFalse();
        assertThat(field(mine, "telegramAvailable").asBoolean()).isFalse();

        var changed = noStore(put(anna, Map.of("emailEnabled", false, "telegramEnabled", true), ME));
        assertThat(field(changed, "emailEnabled").asBoolean()).isFalse();
        assertThat(field(changed, "emailAvailable").asBoolean()).isTrue();
        assertThat(field(changed, "telegramEnabled").asBoolean()).isTrue();
        assertThat(field(changed, "telegramLinked").asBoolean()).isFalse();
        assertThat(field(changed, "telegramAvailable").asBoolean()).isFalse();
        assertThat(noStore(get(anna, ME))).isEqualTo(changed);
        assertThat(preferences.mine(actor(anna)).emailEnabled()).isFalse();
    }

    @Test
    void masterHasTelegramOnlyByDefaultAndChangesIt() {
        var master = signInMaster();

        var mine = noStore(get(master, ME));
        assertThat(field(mine, "emailEnabled").asBoolean()).isFalse();
        assertThat(field(mine, "emailAvailable").asBoolean()).isFalse();
        assertThat(field(mine, "telegramEnabled").asBoolean()).isTrue();

        var changed = noStore(put(master, Map.of("emailEnabled", true, "telegramEnabled", false), ME));
        assertThat(field(changed, "emailEnabled").asBoolean()).isTrue();
        assertThat(field(changed, "telegramEnabled").asBoolean()).isFalse();
        assertThat(noStore(get(master, ME))).isEqualTo(changed);
    }

    /** Пропущенный ключ каналов — не {@code false}: тело без поля отвергается, каналы не меняются. */
    @Test
    void missingChannelIsRejected() {
        var anna = signInClient();
        var before = noStore(get(anna, ME));

        expectInvalid(putJson(anna, "{\"emailEnabled\":false}", ME), "telegramEnabled");
        expectInvalid(putJson(anna, "{\"telegramEnabled\":false}", ME), "emailEnabled");

        assertThat(noStore(get(anna, ME))).isEqualTo(before);
    }

    @Test
    void unknownFieldOfChannelsIsRejected() {
        var anna = signInClient();

        expectInvalid(put(anna, Map.of("emailEnabled", true, "telegramEnabled", true, "sms", true), ME), "sms");
    }

    @Test
    void telegramLinkIsUnavailableWithoutBot() {
        var anna = signInClient();

        var problem = noStore(post(anna, TELEGRAM_LINK), HttpStatus.CONFLICT);

        assertThat(field(problem, "code").asString()).isEqualTo("notifications.telegram_unavailable");
        assertThat(jdbc.sql("select count(*) from notifications.link_token where account_id = ?")
                        .param(anna.accountId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void unlinkingTelegramIsRepeatable() {
        var anna = signInClient();
        jdbc.sql("insert into notifications.telegram_chat (id, account_id, chat_id, linked_at) values (?, ?, ?, now())")
                .params(
                        UUID.randomUUID(),
                        anna.accountId(),
                        ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
                .update();
        assertThat(field(noStore(get(anna, ME)), "telegramLinked").asBoolean()).isTrue();

        noContent(delete(anna, TELEGRAM));
        assertThat(field(noStore(get(anna, ME)), "telegramLinked").asBoolean()).isFalse();
        noContent(delete(anna, TELEGRAM));
        assertThat(field(noStore(get(anna, ME)), "telegramLinked").asBoolean()).isFalse();
    }

    @Test
    void ownChannelsNeedSession() {
        var channels = Map.of("emailEnabled", true, "telegramEnabled", true);

        expectProblem(client.get().uri(ME), HttpStatus.UNAUTHORIZED, "common.unauthenticated");
        expectProblem(
                csrf(client.put().uri(ME))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(channels),
                HttpStatus.UNAUTHORIZED,
                "common.unauthenticated");
        expectProblem(csrf(client.post().uri(TELEGRAM_LINK)), HttpStatus.UNAUTHORIZED, "common.unauthenticated");
        expectProblem(csrf(client.delete().uri(TELEGRAM)), HttpStatus.UNAUTHORIZED, "common.unauthenticated");
    }

    /**
     * Изменяющие запросы без служебного заголовка или с чужим источником не доходят до модуля (спецификация API,
     * §5.2).
     */
    @Test
    void changesNeedCsrfHeaders() {
        var master = signInMaster();
        var channels = Map.of("emailEnabled", true, "telegramEnabled", false);
        var reminders = Map.of("reminderDayBeforeAt", "11:00", "reminderHoursBefore", 5);
        var before = rememberSettings();

        expectCsrf(as(master, client.put().uri(ME).header(HttpHeaders.ORIGIN, SITE))
                .contentType(MediaType.APPLICATION_JSON)
                .body(channels));
        expectCsrf(as(master, client.post().uri(TELEGRAM_LINK).header(HttpHeaders.ORIGIN, SITE)));
        expectCsrf(as(master, client.delete().uri(TELEGRAM).header(HttpHeaders.ORIGIN, SITE)));
        expectCsrf(as(
                        master,
                        client.put()
                                .uri(SETTINGS)
                                .header("X-Requested-With", REQUESTED_WITH)
                                .header(HttpHeaders.ORIGIN, "https://evil.example"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(reminders));

        assertThat(field(noStore(get(master, ME)), "emailEnabled").asBoolean()).isFalse();
        assertThat(settings.settings(actor(master))).isEqualTo(before);
    }

    @Test
    void staffReadsAndChangesReminderSettings() {
        var developer = signInDeveloper();
        var master = signInMaster();
        var original = rememberSettings();
        var dayBeforeAt = LocalTime.of(10, 30).equals(original.dayBeforeAt()) ? "11:30" : "10:30";
        var hoursBefore = Integer.valueOf(3).equals(original.hoursBefore()) ? 4 : 3;

        var read = noStore(get(developer, SETTINGS));
        assertThat(field(read, "reminderDayBeforeAt").isNull()).isEqualTo(original.dayBeforeAt() == null);
        if (original.dayBeforeAt() != null) {
            assertThat(field(read, "reminderDayBeforeAt").asString()).isEqualTo(hhmm(original.dayBeforeAt()));
        }
        assertThat(field(read, "reminderHoursBefore").isNull()).isEqualTo(original.hoursBefore() == null);
        if (original.hoursBefore() != null) {
            assertThat(field(read, "reminderHoursBefore").asInt()).isEqualTo(original.hoursBefore());
        }
        assertThat(read.propertyNames()).containsExactlyInAnyOrder("reminderDayBeforeAt", "reminderHoursBefore");

        var changed = noStore(
                put(master, Map.of("reminderDayBeforeAt", dayBeforeAt, "reminderHoursBefore", hoursBefore), SETTINGS));
        assertThat(field(changed, "reminderDayBeforeAt").asString()).isEqualTo(dayBeforeAt);
        assertThat(field(changed, "reminderHoursBefore").asInt()).isEqualTo(hoursBefore);
        assertThat(settings.settings(actor(master)))
                .isEqualTo(new ReminderSettings(LocalTime.parse(dayBeforeAt), hoursBefore));
        assertThat(noStore(get(master, SETTINGS))).isEqualTo(changed);

        var history = noStore(get(developer, HISTORY));
        var expected = settings.history(actor(developer));
        assertThat(history.size()).isEqualTo(expected.size());
        for (var i = 0; i < 2; i++) {
            var row = history.get(i);
            var change = expected.get(i);
            assertThat(field(row, "field").asString()).isEqualTo(change.field());
            assertThat(field(row, "newValue").asString()).isEqualTo(change.newValue());
            assertThat(field(row, "changedBy").asString())
                    .isEqualTo(master.accountId().toString());
            assertThat(Instant.parse(field(row, "changedAt").asString())).isEqualTo(change.changedAt());
            if (change.oldValue() == null) {
                assertThat(field(row, "oldValue").isNull()).isTrue();
            } else {
                assertThat(field(row, "oldValue").asString()).isEqualTo(change.oldValue());
            }
        }
        assertThat(List.of(history.get(0), history.get(1)))
                .extracting(row -> field(row, "field").asString() + "="
                        + field(row, "newValue").asString())
                .containsExactlyInAnyOrder(
                        "reminder_day_before_at=" + dayBeforeAt, "reminder_hours_before=" + hoursBefore);
    }

    @Test
    void remindersAreSwitchedOffWithNulls() {
        var master = signInMaster();
        rememberSettings();

        var off = noStore(putJson(master, "{\"reminderDayBeforeAt\":null,\"reminderHoursBefore\":null}", SETTINGS));

        assertThat(field(off, "reminderDayBeforeAt").isNull()).isTrue();
        assertThat(field(off, "reminderHoursBefore").isNull()).isTrue();
        assertThat(settings.settings(actor(master))).isEqualTo(new ReminderSettings(null, null));
    }

    /** Нарушение называет поле тела HTTP ({@code reminder…}), а не поле команды модуля. */
    @Test
    void invalidSettingsPointAtBodyFields() {
        var master = signInMaster();
        var before = rememberSettings();

        for (var hours : List.of(0, 25)) {
            expectInvalid(
                    put(master, Map.of("reminderDayBeforeAt", "12:00", "reminderHoursBefore", hours), SETTINGS),
                    "reminderHoursBefore");
        }
        // Тихие часы (22:00–09:00), секунды, нечитаемое время.
        for (var time : List.of("23:00", "08:59", "12:00:30", "noon", "25:00")) {
            expectInvalid(
                    put(master, Map.of("reminderDayBeforeAt", time, "reminderHoursBefore", 2), SETTINGS),
                    "reminderDayBeforeAt");
        }
        // Имена полей команды модуля телу HTTP неизвестны.
        expectInvalid(
                putJson(
                        master,
                        "{\"reminderDayBeforeAt\":\"12:00\",\"reminderHoursBefore\":2,\"dayBeforeAt\":\"12:00\"}",
                        SETTINGS),
                "dayBeforeAt");
        expectInvalid(
                putJson(
                        master,
                        "{\"reminderDayBeforeAt\":\"12:00\",\"reminderHoursBefore\":2,\"hoursBefore\":2}",
                        SETTINGS),
                "hoursBefore");

        assertThat(settings.settings(actor(master))).isEqualTo(before);
    }

    /**
     * Пропущенный ключ — не выключение: выключает напоминание только явный {@code null}, иначе тело без поля (опечатка,
     * старый клиент) молча выключило бы напоминания всем.
     */
    @Test
    void missingSettingIsRejected() {
        var master = signInMaster();
        var before = rememberSettings();

        expectInvalid(putJson(master, "{\"reminderHoursBefore\":2}", SETTINGS), "reminderDayBeforeAt");
        expectInvalid(putJson(master, "{\"reminderDayBeforeAt\":\"12:00\"}", SETTINGS), "reminderHoursBefore");
        var problem = json(putJson(master, "{}", SETTINGS), HttpStatus.BAD_REQUEST);
        assertThat(field(problem, "code").asString()).isEqualTo("common.validation");
        assertThat(field(problem, "errors").values())
                .extracting(error -> field(error, "field").asString())
                .isNotEmpty()
                .isSubsetOf("reminderDayBeforeAt", "reminderHoursBefore");

        assertThat(settings.settings(actor(master))).isEqualTo(before);
    }

    @Test
    void settingsNeedNotificationsSection() {
        var customer = signInClient();
        var body = Map.of("reminderDayBeforeAt", "12:00", "reminderHoursBefore", 2);

        for (var uri : List.of(SETTINGS, HISTORY, DELIVERIES)) {
            expectProblem(get(customer, uri), HttpStatus.NOT_FOUND, "common.not_found");
            expectProblem(client.get().uri(uri), HttpStatus.UNAUTHORIZED, "common.unauthenticated");
        }
        expectProblem(put(customer, body, SETTINGS), HttpStatus.NOT_FOUND, "common.not_found");
        expectProblem(
                csrf(client.put().uri(SETTINGS))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body),
                HttpStatus.UNAUTHORIZED,
                "common.unauthenticated");
    }

    /** Сотрудник с чтением раздела читает, но не меняет; без раздела — честный {@code 403} (спецификация API, §4.6). */
    @Test
    void staffWithoutWriteIsForbidden() {
        var master = signInMaster();
        var before = rememberSettings();
        var body = Map.of("reminderDayBeforeAt", "12:00", "reminderHoursBefore", 2);

        access.setOverride(master.accountId(), Section.NOTIFICATIONS, AccessLevel.READ, developerActor());
        noStore(get(master, SETTINGS));
        noStore(get(master, HISTORY));
        noStore(get(master, DELIVERIES));
        expectProblem(put(master, body, SETTINGS), HttpStatus.FORBIDDEN, "common.access_denied");

        access.setOverride(master.accountId(), Section.NOTIFICATIONS, AccessLevel.NONE, developerActor());
        for (var uri : List.of(SETTINGS, HISTORY, DELIVERIES)) {
            expectProblem(get(master, uri), HttpStatus.FORBIDDEN, "common.access_denied");
        }
        assertThat(settings.settings(developerActor())).isEqualTo(before);
    }

    @Test
    void deliveriesArePagedAndFiltered() {
        var developer = signInDeveloper();
        var booking = ownBooking();
        var card = UUID.randomUUID();
        var start = clock.instant().minus(Duration.ofDays(3));
        var sentEarliest = delivery(booking, card, start, "EMAIL", "SENT", null);
        var blocked =
                delivery(booking, card, start.plus(Duration.ofHours(1)), "TELEGRAM", "FAILED", "telegram_blocked");
        var expiredLatest = delivery(booking, card, start.plus(Duration.ofHours(2)), "EMAIL", "FAILED", "expired");
        // Новые по времени отправки сообщения первыми — порядок модуля.
        var expected = settings.deliveries(new DeliveryFilter(null, booking), new Paging(0, 50), actor(developer))
                .items();
        assertThat(expected).extracting(DeliveryView::deliveryId).containsExactly(expiredLatest, blocked, sentEarliest);

        var first = noStore(get(developer, DELIVERIES + "?bookingId={b}&page=0&size=2", booking));
        var second = noStore(get(developer, DELIVERIES + "?bookingId={b}&page=1&size=2", booking));

        assertThat(field(first, "total").asLong()).isEqualTo(3);
        assertThat(field(first, "page").asInt()).isZero();
        assertThat(field(first, "size").asInt()).isEqualTo(2);
        assertThat(field(second, "page").asInt()).isEqualTo(1);
        assertThat(deliveryIds(field(first, "items")))
                .containsExactly(expected.get(0).deliveryId(), expected.get(1).deliveryId());
        assertThat(deliveryIds(field(second, "items")))
                .containsExactly(expected.get(2).deliveryId());
        expectDelivery(field(first, "items").get(0), expected.get(0));
        expectDelivery(field(first, "items").get(1), expected.get(1));
        expectDelivery(field(second, "items").get(0), expected.get(2));

        var failed = noStore(get(developer, DELIVERIES + "?bookingId={b}&status=failed", booking));
        assertThat(field(failed, "total").asLong()).isEqualTo(2);
        assertThat(field(failed, "size").asInt()).isEqualTo(50);
        assertThat(deliveryIds(field(failed, "items"))).containsExactly(expiredLatest, blocked);
        var sent = noStore(get(developer, DELIVERIES + "?bookingId={b}&status=sent", booking));
        assertThat(deliveryIds(field(sent, "items"))).containsExactly(sentEarliest);
        var pending = noStore(get(developer, DELIVERIES + "?bookingId={b}&status=pending", booking));
        assertThat(field(pending, "total").asLong()).isZero();
        assertThat(field(pending, "items").isEmpty()).isTrue();

        var any = noStore(get(developer, DELIVERIES + "?status=failed&size=200"));
        assertThat(field(any, "total").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(field(any, "items").values())
                .allSatisfy(item -> assertThat(field(item, "status").asString()).isEqualTo("FAILED"));
    }

    @Test
    void invalidDeliveryParametersAreRejected() {
        var developer = signInDeveloper();

        expectInvalid(get(developer, DELIVERIES + "?status=FAILED"), "status");
        expectInvalid(get(developer, DELIVERIES + "?status=lost"), "status");
        expectInvalid(get(developer, DELIVERIES + "?bookingId=x"), "bookingId");
        expectInvalid(get(developer, DELIVERIES + "?page=-1"), "page");
        expectInvalid(get(developer, DELIVERIES + "?size=0"), "size");
        expectInvalid(get(developer, DELIVERIES + "?size=201"), "size");
    }

    /** Клиентка с почтой у провайдера: почта попадает в карточку при регистрации. */
    private Session signInClientWithEmail() {
        var subject = uniqueSubject();
        return signIn(new FakeProfile(subject, uniquePhone(), subject + "@example.com", "Анна", null));
    }

    private ReminderSettings rememberSettings() {
        var original = settings.settings(developerActor());
        originalSettings = original;
        return original;
    }

    private UUID ownBooking() {
        var booking = UUID.randomUUID();
        ownBookings.add(booking);
        return booking;
    }

    /** Отправленное сообщение клиентке о записи и его доставка по каналу; возвращает id доставки. */
    private UUID delivery(
            UUID booking, UUID client, Instant dueAt, String channel, String status, @Nullable String errorCode) {
        var message = UUID.randomUUID();
        var due = OffsetDateTime.ofInstant(dueAt, ZoneOffset.UTC);
        jdbc.sql("""
                        insert into notifications.message (id, client_id, kind, booking_id, due_at, expires_at, status,
                            dedup_key, occurred_at, created_at, dispatched_at)
                        values (:id, :client, 'CONFIRMED', :booking, :due, :expires, 'DISPATCHED', :key, :due, :due,
                            :due)
                        """)
                .param("id", message)
                .param("client", client)
                .param("booking", booking)
                .param("due", due)
                .param("expires", due.plusDays(1))
                .param("key", "api-test:" + message)
                .update();
        var delivery = UUID.randomUUID();
        jdbc.sql("""
                        insert into notifications.delivery (id, message_id, channel, status, attempts, next_attempt_at,
                            sent_at, error_code)
                        values (:id, :message, :channel, :status, 1, :due, :sent, :error)
                        """)
                .param("id", delivery)
                .param("message", message)
                .param("channel", channel)
                .param("status", status)
                .param("due", due)
                .param("sent", status.equals("SENT") ? due.plusMinutes(1) : null)
                .param("error", errorCode)
                .update();
        return delivery;
    }

    /** Строка журнала по HTTP — та же, что у модуля, поле в поле. */
    private static void expectDelivery(JsonNode item, DeliveryView view) {
        assertThat(item.propertyNames())
                .containsExactlyInAnyOrder(
                        "deliveryId",
                        "messageId",
                        "kind",
                        "bookingId",
                        "clientId",
                        "accountId",
                        "channel",
                        "status",
                        "attempts",
                        "errorCode",
                        "dueAt",
                        "sentAt");
        assertThat(field(item, "deliveryId").asString())
                .isEqualTo(view.deliveryId().toString());
        assertThat(field(item, "messageId").asString())
                .isEqualTo(view.messageId().toString());
        assertThat(field(item, "kind").asString()).isEqualTo(view.kind().name());
        assertThat(field(item, "bookingId").asString())
                .isEqualTo(view.bookingId().toString());
        assertThat(field(item, "clientId").asString())
                .isEqualTo(Objects.requireNonNull(view.clientId()).toString());
        assertThat(field(item, "accountId").isNull()).isTrue();
        assertThat(field(item, "channel").asString()).isEqualTo(view.channel().name());
        assertThat(field(item, "status").asString()).isEqualTo(view.status().name());
        assertThat(field(item, "attempts").asInt()).isEqualTo(view.attempts());
        if (view.errorCode() == null) {
            assertThat(field(item, "errorCode").isNull()).isTrue();
        } else {
            assertThat(field(item, "errorCode").asString()).isEqualTo(view.errorCode());
        }
        assertThat(Instant.parse(field(item, "dueAt").asString())).isEqualTo(view.dueAt());
        if (view.status() == DeliveryStatus.SENT) {
            assertThat(Instant.parse(field(item, "sentAt").asString())).isEqualTo(view.sentAt());
        } else {
            assertThat(field(item, "sentAt").isNull()).isTrue();
        }
    }

    private static List<UUID> deliveryIds(JsonNode items) {
        return items.values().stream()
                .map(item -> UUID.fromString(field(item, "deliveryId").asString()))
                .toList();
    }

    private static String hhmm(LocalTime time) {
        return "%02d:%02d".formatted(time.getHour(), time.getMinute());
    }

    /** Тело ответа {@code 200}, который запрещено сохранять: {@code Cache-Control: no-store}. */
    private static JsonNode noStore(RequestHeadersSpec<?> spec) {
        return noStore(spec, HttpStatus.OK);
    }

    /** Тело ответа с ожидаемым статусом, который запрещено сохранять: {@code Cache-Control: no-store}. */
    private static JsonNode noStore(RequestHeadersSpec<?> spec, HttpStatus status) {
        return Objects.requireNonNull(spec.exchange()
                .expectStatus()
                .isEqualTo(status)
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody());
    }

    /** {@code 204} без тела, который запрещено сохранять. */
    private static void noContent(RequestHeadersSpec<?> spec) {
        spec.exchange()
                .expectStatus()
                .isNoContent()
                .expectHeader()
                .valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody()
                .isEmpty();
    }

    private static void expectInvalid(RequestHeadersSpec<?> spec, String field) {
        var problem = json(spec, HttpStatus.BAD_REQUEST);
        assertThat(field(problem, "code").asString()).isEqualTo("common.validation");
        assertThat(field(problem, "errors").values())
                .extracting(error -> field(error, "field").asString())
                .containsExactly(field);
    }

    private static void expectCsrf(RequestHeadersSpec<?> spec) {
        expectProblem(spec, HttpStatus.FORBIDDEN, "common.csrf");
    }

    private RequestHeadersSpec<?> get(Session session, String uri, Object... variables) {
        return as(session, client.get().uri(uri, variables));
    }

    private RequestHeadersSpec<?> put(Session session, Object body, String uri) {
        return as(session, csrf(client.put().uri(uri)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private RequestHeadersSpec<?> putJson(Session session, String json, String uri) {
        return as(session, csrf(client.put().uri(uri)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    private RequestHeadersSpec<?> post(Session session, String uri) {
        return as(session, csrf(client.post().uri(uri)));
    }

    private RequestHeadersSpec<?> delete(Session session, String uri) {
        return as(session, csrf(client.delete().uri(uri)));
    }
}
