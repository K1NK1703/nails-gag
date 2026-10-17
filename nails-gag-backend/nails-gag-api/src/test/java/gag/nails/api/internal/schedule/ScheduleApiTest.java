package gag.nails.api.internal.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import gag.nails.booking.BookingService;
import gag.nails.booking.command.CreateBooking;
import gag.nails.schedule.ScheduleQueries;
import gag.nails.schedule.ScheduleService;
import gag.nails.schedule.ScheduleSettingsService;
import gag.nails.schedule.ScheduleTemplates;
import gag.nails.schedule.command.CreateDayTemplate;
import gag.nails.schedule.command.CreateWeekTemplate;
import gag.nails.schedule.command.UpdateDayTemplate;
import gag.nails.schedule.command.UpdateWeekTemplate;
import gag.nails.schedule.model.DayTemplateView;
import gag.nails.schedule.model.SettingsChangeView;
import gag.nails.schedule.model.SlotView;
import gag.nails.schedule.model.WeekTemplateView;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient.RequestHeadersSpec;
import tools.jackson.databind.JsonNode;

/**
 * Расписание (спецификация API, §7.5): свободные слоты и настройки — любому вошедшему, слоты, шаблоны, ротация и
 * журнал настроек — по разделу «Расписание», его проверяет модуль. Ротация и длительность слота в базе одни на всех:
 * тесты, которые их меняют, не оставляют длительность изменённой, а слоты кладут на свои дни ({@link #freshDay()}).
 * Ротация и шаблон дня по умолчанию тоже общие для всех тестов закэшированного контекста, и тесты оставляют их
 * изменёнными: ни один тест не опирается на их начальное состояние.
 */
@ApiTest
class ScheduleApiTest extends ApiTestSupport {

    private static final LocalTime TEN = LocalTime.of(10, 0);
    private static final LocalTime ELEVEN = LocalTime.of(11, 0);
    private static final LocalTime TWO_PM = LocalTime.of(14, 0);

    /** Адреса с периодом {@code from}/{@code to}: у каждого период проверяется до вызова модуля. */
    enum PeriodEndpoint {
        FREE_SLOTS,
        SLOTS,
        ROTATION_PREVIEW,
        ROTATION_APPLY
    }

    @Autowired
    ScheduleService schedule;

    @Autowired
    ScheduleQueries scheduleQueries;

    @Autowired
    ScheduleTemplates templates;

    @Autowired
    ScheduleSettingsService settings;

    @Autowired
    BookingService bookings;

    @Test
    void freeSlotsRequireSession() {
        var day = freshDay();

        expectProblem(
                client.get().uri("/api/schedule/free-slots?from={from}&to={to}", day, day),
                HttpStatus.UNAUTHORIZED,
                "common.unauthenticated");
    }

    @Test
    void clientSeesFreeSlots() {
        var day = freshDay();
        var free = schedule.addSlot(day, TEN, developerActor());
        var occupied = occupiedSlot(day, TWO_PM);

        var slots =
                json(as(signInClient(), client.get().uri("/api/schedule/free-slots?from={from}&to={to}", day, day)));

        assertThat(ids(slots)).containsExactly(free.id()).doesNotContain(occupied.id());
    }

    @ParameterizedTest
    @EnumSource(PeriodEndpoint.class)
    void periodLongerThan93DaysIsRejected(PeriodEndpoint endpoint) {
        var from = freshDay();
        var to = from.plusDays(93);

        var problem = json(periodRequest(signInMaster(), endpoint, from, to), HttpStatus.BAD_REQUEST);

        assertThat(field(problem, "code").asString()).isEqualTo("common.validation");
        assertThat(field(field(problem, "errors").path(0), "field").asString()).isEqualTo("to");
    }

    @Test
    void masterAddsMovesAndDeletesSlot() {
        var master = signInMaster();
        var day = freshDay();

        var created = json(addSlot(master, day, "10:00"), HttpStatus.CREATED);
        var slotId = UUID.fromString(field(created, "id").asString());
        assertThat(field(created, "day").asString()).isEqualTo(day.toString());
        assertThat(field(created, "startsAt").asString())
                .isEqualTo(instant(day, TEN).toString());
        assertThat(field(created, "occupied").isBoolean()).isTrue();
        assertThat(field(created, "occupied").asBoolean()).isFalse();
        assertThat(field(created, "bookingId").isNull()).isTrue();

        var moved = json(moveSlot(master, slotId, "11:00"));
        assertThat(field(moved, "id").asString()).isEqualTo(slotId.toString());
        assertThat(Instant.parse(field(moved, "startsAt").asString())).isEqualTo(instant(day, ELEVEN));

        var ofDay = json(as(master, client.get().uri("/api/schedule/slots?from={from}&to={to}", day, day)));
        assertThat(ids(ofDay)).containsExactly(slotId);
        assertThat(scheduleQueries.slotsOfDay(day, developerActor()))
                .extracting(SlotView::startsAt)
                .containsExactly(instant(day, ELEVEN));

        as(master, csrf(client.delete().uri("/api/schedule/slots/{id}", slotId)))
                .exchange()
                .expectStatus()
                .isNoContent();
        assertThat(scheduleQueries.slotsOfDay(day, developerActor())).isEmpty();
    }

    @Test
    void overlappingSlotConflicts() {
        var day = freshDay();
        schedule.addSlot(day, TEN, developerActor());

        expectProblem(addSlot(signInMaster(), day, "10:30"), HttpStatus.CONFLICT, "schedule.slot_overlap");
    }

    @Test
    void occupiedSlotCannotBeDeleted() {
        var slot = occupiedSlot(freshDay(), TEN);

        expectProblem(
                as(signInMaster(), csrf(client.delete().uri("/api/schedule/slots/{id}", slot.id()))),
                HttpStatus.CONFLICT,
                "schedule.slot_occupied");
    }

    @Test
    void clientCannotAddSlot() {
        expectProblem(addSlot(signInClient(), freshDay(), "10:00"), HttpStatus.NOT_FOUND, "common.not_found");
    }

    @Test
    void clientCannotReadSlots() {
        var day = freshDay();

        expectProblem(
                as(signInClient(), client.get().uri("/api/schedule/slots?from={from}&to={to}", day, day)),
                HttpStatus.NOT_FOUND,
                "common.not_found");
    }

    @Test
    void masterManagesDayTemplatesAndDefault() {
        var master = signInMaster();
        var name = uniqueName();

        var created = json(
                as(master, csrf(client.post().uri("/api/schedule/day-templates")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new CreateDayTemplate(name, List.of(TEN, TWO_PM))),
                HttpStatus.CREATED);
        var firstId = UUID.fromString(field(created, "id").asString());
        assertThat(field(created, "name").asString()).isEqualTo(name);
        assertThat(field(created, "startTimes").values())
                .extracting(JsonNode::asString)
                .containsExactly("10:00", "14:00");

        var newName = uniqueName();
        var updated = json(as(master, csrf(client.patch().uri("/api/schedule/day-templates/{id}", firstId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UpdateDayTemplate(newName, List.of(TEN))));
        assertThat(field(updated, "name").asString()).isEqualTo(newName);
        assertThat(field(updated, "startTimes").values())
                .extracting(JsonNode::asString)
                .containsExactly("10:00");

        var secondId = createDayTemplate().id();
        assertThat(field(json(setDefault(master, firstId)), "isDefault").asBoolean())
                .isTrue();
        assertThat(field(json(setDefault(master, secondId)), "isDefault").asBoolean())
                .isTrue();

        var listed = json(as(master, client.get().uri("/api/schedule/day-templates")));
        assertThat(isDefault(byId(listed, firstId))).isFalse();
        assertThat(isDefault(byId(listed, secondId))).isTrue();
        assertThat(listed.values().stream().filter(ScheduleApiTest::isDefault)).hasSize(1);

        as(master, csrf(client.delete().uri("/api/schedule/day-templates/{id}", firstId)))
                .exchange()
                .expectStatus()
                .isNoContent();
        assertThat(templates.dayTemplates(developerActor()))
                .extracting(DayTemplateView::id)
                .doesNotContain(firstId);
    }

    @Test
    void dayTemplateOfWeekTemplateCannotBeDeleted() {
        var dayTemplate = createDayTemplate();
        templates.createWeekTemplate(
                new CreateWeekTemplate(uniqueName(), Map.of(DayOfWeek.MONDAY, dayTemplate.id())), developerActor());

        expectProblem(
                as(signInMaster(), csrf(client.delete().uri("/api/schedule/day-templates/{id}", dayTemplate.id()))),
                HttpStatus.CONFLICT,
                "schedule.day_template_in_use");
    }

    @Test
    void masterAppliesDayTemplateSkippingOpenDays() {
        var template = createDayTemplate();
        var openDay = freshDay();
        var emptyDay = freshDay();
        schedule.addSlot(openDay, TEN, developerActor());

        var application = json(applyDayTemplate(signInMaster(), template.id(), List.of(openDay, emptyDay)));

        assertThat(field(application, "created").values())
                .extracting(slot -> field(slot, "day").asString())
                .containsExactly(emptyDay.toString(), emptyDay.toString());
        assertThat(field(application, "skipped").values())
                .extracting(JsonNode::asString)
                .containsExactly(openDay.toString());
    }

    @Test
    void moreDatesThanPeriodAreRejected() {
        var template = createDayTemplate();
        // Дни выдаёт freshDay(): если ограничение пропустит запрос, слоты лягут на дни, которых нет у других тестов.
        var dates = Stream.generate(this::freshDay).limit(94).toList();

        var problem = json(applyDayTemplate(signInMaster(), template.id(), dates), HttpStatus.BAD_REQUEST);

        assertThat(field(problem, "code").asString()).isEqualTo("common.validation");
        assertThat(field(field(problem, "errors").path(0), "field").asString()).isEqualTo("dates");
    }

    @Test
    void masterManagesWeekTemplates() {
        var master = signInMaster();
        var dayTemplate = createDayTemplate();
        var name = uniqueName();

        var created = json(
                as(master, csrf(client.post().uri("/api/schedule/week-templates")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new CreateWeekTemplate(name, Map.of(DayOfWeek.MONDAY, dayTemplate.id()))),
                HttpStatus.CREATED);
        var templateId = UUID.fromString(field(created, "id").asString());
        assertThat(field(field(created, "days"), "MONDAY").asString())
                .isEqualTo(dayTemplate.id().toString());

        var newName = uniqueName();
        var updated = json(as(master, csrf(client.patch().uri("/api/schedule/week-templates/{id}", templateId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UpdateWeekTemplate(newName, Map.of(DayOfWeek.FRIDAY, dayTemplate.id()))));
        assertThat(field(updated, "name").asString()).isEqualTo(newName);
        assertThat(field(updated, "days").size()).isEqualTo(1);
        assertThat(field(field(updated, "days"), "FRIDAY").asString())
                .isEqualTo(dayTemplate.id().toString());

        var listed = json(as(master, client.get().uri("/api/schedule/week-templates")));
        assertThat(field(byId(listed, templateId), "name").asString()).isEqualTo(newName);

        as(master, csrf(client.delete().uri("/api/schedule/week-templates/{id}", templateId)))
                .exchange()
                .expectStatus()
                .isNoContent();
        assertThat(templates.weekTemplates(developerActor()))
                .extracting(WeekTemplateView::id)
                .doesNotContain(templateId);
    }

    @Test
    void masterSetsPreviewsAndAppliesRotation() {
        var master = signInMaster();
        var dayTemplate = createDayTemplate();
        var everyDay = Arrays.stream(DayOfWeek.values())
                .collect(Collectors.toMap(Function.identity(), dayOfWeek -> dayTemplate.id()));
        var week = templates.createWeekTemplate(new CreateWeekTemplate(uniqueName(), everyDay), developerActor());
        var day = freshDay();
        var monday = day.with(DayOfWeek.MONDAY);

        var rotation = json(as(master, csrf(client.put().uri("/api/schedule/rotation")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("weekTemplateIds", List.of(week.id()), "anchorMonday", monday, "anchorIndex", 0)));
        assertThat(field(rotation, "weekTemplateIds").values())
                .extracting(JsonNode::asString)
                .containsExactly(week.id().toString());

        var current = json(as(master, client.get().uri("/api/schedule/rotation")));
        assertThat(field(current, "anchorMonday").asString()).isEqualTo(monday.toString());

        var preview =
                json(as(master, client.get().uri("/api/schedule/rotation/preview?from={from}&to={to}", day, day)));
        var weeks = field(preview, "weeks");
        assertThat(weeks.size()).isEqualTo(1);
        assertThat(field(weeks.path(0), "weekTemplateId").asString())
                .isEqualTo(week.id().toString());
        var days = field(weeks.path(0), "days");
        assertThat(days.size()).isEqualTo(1);
        assertThat(field(days.path(0), "date").asString()).isEqualTo(day.toString());
        assertThat(field(days.path(0), "status").asString()).isEqualTo("PLANNED");

        var application = json(applyRotation(master, day, day, monday, week.id()));
        assertThat(field(application, "created").size()).isEqualTo(2);
        assertThat(scheduleQueries.slotsOfDay(day, developerActor()))
                .extracting(SlotView::startsAt)
                .containsExactly(instant(day, TEN), instant(day, TWO_PM));
    }

    @Test
    void settingsReadableBySessionAndSlotDurationByMaster() {
        var current = settings.settings().slotDurationMinutes();

        expectProblem(client.get().uri("/api/schedule/settings"), HttpStatus.UNAUTHORIZED, "common.unauthenticated");
        var read = json(as(signInClient(), client.get().uri("/api/schedule/settings")));
        assertThat(field(read, "slotDurationMinutes").asInt()).isEqualTo(current);

        var changed = otherDuration(current);
        try {
            var set = json(setSlotDuration(signInMaster(), changed));
            assertThat(field(set, "slotDurationMinutes").asInt()).isEqualTo(changed);
        } finally {
            settings.setSlotDuration(current, developerActor());
        }
    }

    @Test
    void clientCannotSetSlotDuration() {
        var current = settings.settings().slotDurationMinutes();

        expectProblem(
                setSlotDuration(signInClient(), otherDuration(current)), HttpStatus.NOT_FOUND, "common.not_found");
        assertThat(settings.settings().slotDurationMinutes()).isEqualTo(current);
    }

    /**
     * Журнал общий для всей базы, а часы в тестах стоят: такая же строка могла остаться от прошлых тестов и прогонов,
     * поэтому проверяется, что совпадающих строк стало на одну больше.
     */
    @Test
    void slotDurationChangeAppearsInHistory() {
        var current = settings.settings().slotDurationMinutes();
        var changed = otherDuration(current);
        var before = settings.history(developerActor()).stream()
                .filter(change -> isDurationChange(change, current, changed))
                .count();
        try {
            settings.setSlotDuration(changed, developerActor());

            var history = json(as(signInMaster(), client.get().uri("/api/schedule/settings/history")));

            var matching = history.values().stream()
                    .filter(entry -> field(entry, "field").asString().equals("slot_duration_minutes")
                            && field(entry, "oldValue").asString().equals(String.valueOf(current))
                            && field(entry, "newValue").asString().equals(String.valueOf(changed)))
                    .count();
            assertThat(matching).isEqualTo(before + 1);
        } finally {
            settings.setSlotDuration(current, developerActor());
        }
    }

    private RequestHeadersSpec<?> addSlot(Session session, LocalDate date, String startTime) {
        return as(session, csrf(client.post().uri("/api/schedule/slots")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("date", date, "startTime", startTime));
    }

    private RequestHeadersSpec<?> moveSlot(Session session, UUID slotId, String startTime) {
        return as(session, csrf(client.post().uri("/api/schedule/slots/{id}/move", slotId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("startTime", startTime));
    }

    private RequestHeadersSpec<?> applyDayTemplate(Session session, UUID templateId, List<LocalDate> dates) {
        return as(session, csrf(client.post().uri("/api/schedule/days/apply-template")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("templateId", templateId, "dates", dates));
    }

    private RequestHeadersSpec<?> setDefault(Session session, UUID templateId) {
        return as(session, csrf(client.post().uri("/api/schedule/day-templates/{id}/set-default", templateId)));
    }

    private RequestHeadersSpec<?> applyRotation(
            Session session, LocalDate from, LocalDate to, LocalDate monday, UUID weekTemplateId) {
        return as(session, csrf(client.post().uri("/api/schedule/rotation/apply")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "from",
                        from,
                        "to",
                        to,
                        "weeks",
                        List.of(Map.of("monday", monday, "weekTemplateId", weekTemplateId))));
    }

    private RequestHeadersSpec<?> setSlotDuration(Session session, int minutes) {
        return as(session, csrf(client.put().uri("/api/schedule/settings/slot-duration")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("minutes", minutes));
    }

    /** Запрос к адресу с периодом; у применения ротации тело в остальном верное, шаблон недели — любой. */
    private RequestHeadersSpec<?> periodRequest(
            Session session, PeriodEndpoint endpoint, LocalDate from, LocalDate to) {
        var period = "?from={from}&to={to}";
        return switch (endpoint) {
            case FREE_SLOTS -> as(session, client.get().uri("/api/schedule/free-slots" + period, from, to));
            case SLOTS -> as(session, client.get().uri("/api/schedule/slots" + period, from, to));
            case ROTATION_PREVIEW -> as(session, client.get().uri("/api/schedule/rotation/preview" + period, from, to));
            case ROTATION_APPLY -> applyRotation(session, from, to, from.with(DayOfWeek.MONDAY), UUID.randomUUID());
        };
    }

    /** Единственный элемент массива с данным {@code id}. */
    private static JsonNode byId(JsonNode items, UUID id) {
        var found = items.values().stream()
                .filter(item -> field(item, "id").asString().equals(id.toString()))
                .toList();
        assertThat(found).as("items with id " + id).hasSize(1);
        return found.getFirst();
    }

    private static boolean isDefault(JsonNode template) {
        var isDefault = field(template, "isDefault");
        assertThat(isDefault.isBoolean()).as("isDefault is boolean").isTrue();
        return isDefault.asBoolean();
    }

    private static boolean isDurationChange(SettingsChangeView change, int oldValue, int newValue) {
        return change.field().equals("slot_duration_minutes")
                && change.oldValue().equals(String.valueOf(oldValue))
                && change.newValue().equals(String.valueOf(newValue));
    }

    /** Момент начала по дате и времени салона: пояс подставных часов — пояс салона. */
    private Instant instant(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(clock.getZone()).toInstant();
    }

    /** Слот, занятый записью новой карточки клиента; всё заводит разработчик. */
    private SlotView occupiedSlot(LocalDate day, LocalTime startTime) {
        var developer = developerActor();
        var service = mainService();
        var slot = schedule.addSlot(day, startTime, developer);
        var customer = createClient(uniqueLastName());
        bookings.createByMaster(
                new CreateBooking(customer.id(), slot.id(), service.id(), List.of(), null), false, developer);
        return slot;
    }

    /** Шаблон дня на 10:00 и 14:00, его заводит разработчик; времена не пересекаются ни при какой длительности теста. */
    private DayTemplateView createDayTemplate() {
        return templates.createDayTemplate(new CreateDayTemplate(uniqueName(), List.of(TEN, TWO_PM)), developerActor());
    }

    /** Другая допустимая длительность: на 15 минут короче текущей, у самой короткой — длиннее. */
    private static int otherDuration(int current) {
        return current > ScheduleSettingsService.MIN_SLOT_DURATION_MINUTES ? current - 15 : current + 15;
    }

    private static String uniqueName() {
        return "Шаблон " + UUID.randomUUID();
    }
}
