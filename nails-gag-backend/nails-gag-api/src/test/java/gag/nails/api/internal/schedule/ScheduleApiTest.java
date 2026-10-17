package gag.nails.api.internal.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import gag.nails.schedule.ScheduleQueries;
import gag.nails.schedule.ScheduleService;
import gag.nails.schedule.ScheduleSettingsService;
import gag.nails.schedule.ScheduleTemplates;
import gag.nails.schedule.command.CreateDayTemplate;
import gag.nails.schedule.command.CreateWeekTemplate;
import gag.nails.schedule.command.UpdateDayTemplate;
import gag.nails.schedule.command.UpdateWeekTemplate;
import gag.nails.schedule.model.DayTemplateView;
import gag.nails.schedule.model.WeekTemplateView;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient.RequestHeadersSpec;
import tools.jackson.databind.JsonNode;

/**
 * Расписание (спецификация API, §7.5): свободные слоты и настройки — любому вошедшему, слоты, шаблоны, ротация и
 * журнал настроек — по разделу «Расписание», его проверяет модуль. Ротация и длительность слота в базе одни на всех:
 * тесты, которые их меняют, не оставляют длительность изменённой, а слоты кладут на свои дни ({@link #freshDay()}).
 */
@ApiTest
class ScheduleApiTest extends ApiTestSupport {

    private static final LocalTime TEN = LocalTime.of(10, 0);
    private static final LocalTime TWO_PM = LocalTime.of(14, 0);

    @Autowired
    ScheduleService schedule;

    @Autowired
    ScheduleQueries scheduleQueries;

    @Autowired
    ScheduleTemplates templates;

    @Autowired
    ScheduleSettingsService settings;

    @Test
    void freeSlotsNeedSession() {
        var day = freshDay();
        var slot = schedule.addSlot(day, TEN, developerActor());

        client.get()
                .uri("/api/schedule/free-slots?from={from}&to={to}", day, day)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.unauthenticated");

        var free = json(as(signInClient(), client.get().uri("/api/schedule/free-slots?from={from}&to={to}", day, day)));
        assertThat(ids(free)).containsExactly(slot.id());
    }

    @Test
    void periodLongerThan93DaysIsRejected() {
        var from = freshDay();

        as(signInMaster(), client.get().uri("/api/schedule/slots?from={from}&to={to}", from, from.plusDays(93)))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.validation")
                .jsonPath("$.errors[0].field")
                .isEqualTo("to");
    }

    @Test
    void masterAddsMovesAndDeletesSlot() {
        var master = signInMaster();
        var day = freshDay();

        var created = json(
                as(master, csrf(client.post().uri("/api/schedule/slots")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("date", day, "startTime", "10:00")),
                201);
        var slotId = UUID.fromString(created.path("id").asString());
        assertThat(created.path("day").asString()).isEqualTo(day.toString());
        assertThat(created.path("occupied").asBoolean()).isFalse();

        var moved = json(as(master, csrf(client.post().uri("/api/schedule/slots/{id}/move", slotId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("startTime", "11:00")));
        assertThat(moved.path("id").asString()).isEqualTo(slotId.toString());
        assertThat(moved.path("startsAt").asString())
                .isNotEqualTo(created.path("startsAt").asString());

        var ofDay = json(as(master, client.get().uri("/api/schedule/slots?from={from}&to={to}", day, day)));
        assertThat(ids(ofDay)).containsExactly(slotId);
        assertThat(ofDay.path(0).path("startsAt").asString())
                .isEqualTo(moved.path("startsAt").asString());

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

        as(signInMaster(), csrf(client.post().uri("/api/schedule/slots")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("date", day, "startTime", "10:30"))
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("schedule.slot_overlap");
    }

    @Test
    void clientCannotAddSlot() {
        as(signInClient(), csrf(client.post().uri("/api/schedule/slots")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("date", freshDay(), "startTime", "10:00"))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.not_found");
    }

    @Test
    void masterManagesDayTemplatesAndDefault() {
        var master = signInMaster();
        var name = uniqueName();

        var created = json(
                as(master, csrf(client.post().uri("/api/schedule/day-templates")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new CreateDayTemplate(name, List.of(TEN, TWO_PM))),
                201);
        var templateId = UUID.fromString(created.path("id").asString());
        assertThat(created.path("name").asString()).isEqualTo(name);
        assertThat(created.path("startTimes").path(0).asString()).isEqualTo("10:00");

        var newName = uniqueName();
        var updated = json(as(master, csrf(client.patch().uri("/api/schedule/day-templates/{id}", templateId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UpdateDayTemplate(newName, List.of(TEN))));
        assertThat(updated.path("name").asString()).isEqualTo(newName);
        assertThat(updated.path("startTimes").size()).isEqualTo(1);

        var madeDefault =
                json(as(master, csrf(client.post().uri("/api/schedule/day-templates/{id}/set-default", templateId))));
        assertThat(madeDefault.path("isDefault").asBoolean()).isTrue();

        var listed = byId(json(as(master, client.get().uri("/api/schedule/day-templates"))));
        assertThat(listed.get(templateId).path("isDefault").asBoolean()).isTrue();
        assertThat(listed.values().stream()
                        .filter(template -> template.path("isDefault").asBoolean()))
                .hasSize(1);

        as(master, csrf(client.delete().uri("/api/schedule/day-templates/{id}", templateId)))
                .exchange()
                .expectStatus()
                .isNoContent();
        assertThat(templates.dayTemplates(developerActor()))
                .extracting(DayTemplateView::id)
                .doesNotContain(templateId);
    }

    @Test
    void masterAppliesDayTemplateSkippingOpenDays() {
        var template = createDayTemplate();
        var openDay = freshDay();
        var emptyDay = freshDay();
        schedule.addSlot(openDay, TEN, developerActor());

        var application = json(as(signInMaster(), csrf(client.post().uri("/api/schedule/days/apply-template")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("templateId", template.id(), "dates", List.of(openDay, emptyDay))));

        assertThat(application.path("created").size()).isEqualTo(2);
        assertThat(application.path("created").path(0).path("day").asString()).isEqualTo(emptyDay.toString());
        assertThat(application.path("skipped").path(0).asString()).isEqualTo(openDay.toString());
        assertThat(application.path("skipped").size()).isEqualTo(1);
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
                201);
        var templateId = UUID.fromString(created.path("id").asString());
        assertThat(created.path("days").path("MONDAY").asString())
                .isEqualTo(dayTemplate.id().toString());

        var newName = uniqueName();
        var updated = json(as(master, csrf(client.patch().uri("/api/schedule/week-templates/{id}", templateId)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(new UpdateWeekTemplate(newName, Map.of(DayOfWeek.FRIDAY, dayTemplate.id()))));
        assertThat(updated.path("name").asString()).isEqualTo(newName);
        assertThat(updated.path("days").has("MONDAY")).isFalse();
        assertThat(updated.path("days").path("FRIDAY").asString())
                .isEqualTo(dayTemplate.id().toString());

        var listed = byId(json(as(master, client.get().uri("/api/schedule/week-templates"))));
        assertThat(listed.get(templateId).path("name").asString()).isEqualTo(newName);

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
        assertThat(rotation.path("weekTemplateIds").path(0).asString())
                .isEqualTo(week.id().toString());

        var current = json(as(master, client.get().uri("/api/schedule/rotation")));
        assertThat(current.path("anchorMonday").asString()).isEqualTo(monday.toString());

        var preview =
                json(as(master, client.get().uri("/api/schedule/rotation/preview?from={from}&to={to}", day, day)));
        var plannedDay = preview.path("weeks").path(0).path("days").path(0);
        assertThat(plannedDay.path("date").asString()).isEqualTo(day.toString());
        assertThat(plannedDay.path("status").asString()).isEqualTo("PLANNED");

        var application = json(as(master, csrf(client.post().uri("/api/schedule/rotation/apply")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "from",
                        day,
                        "to",
                        day,
                        "weeks",
                        List.of(Map.of("monday", monday, "weekTemplateId", week.id())))));
        assertThat(application.path("created").size()).isEqualTo(2);
        assertThat(scheduleQueries.slotsOfDay(day, developerActor())).hasSize(2);
    }

    @Test
    void settingsReadableBySessionAndSlotDurationByMaster() {
        var current = settings.settings().slotDurationMinutes();

        client.get().uri("/api/schedule/settings").exchange().expectStatus().isUnauthorized();
        as(signInClient(), client.get().uri("/api/schedule/settings"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.slotDurationMinutes")
                .isEqualTo(current);

        var changed = otherDuration(current);
        try {
            setSlotDuration(signInMaster(), changed)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.slotDurationMinutes")
                    .isEqualTo(changed);
        } finally {
            settings.setSlotDuration(current, developerActor());
        }
    }

    @Test
    void clientCannotSetSlotDuration() {
        var current = settings.settings().slotDurationMinutes();

        setSlotDuration(signInClient(), otherDuration(current))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.not_found");
        assertThat(settings.settings().slotDurationMinutes()).isEqualTo(current);
    }

    @Test
    void slotDurationChangeAppearsInHistory() {
        var current = settings.settings().slotDurationMinutes();
        var changed = otherDuration(current);
        try {
            settings.setSlotDuration(changed, developerActor());

            var history = json(as(signInMaster(), client.get().uri("/api/schedule/settings/history")));

            var latest = history.path(0);
            assertThat(latest.path("field").asString()).isEqualTo("slot_duration_minutes");
            assertThat(latest.path("oldValue").asString()).isEqualTo(String.valueOf(current));
            assertThat(latest.path("newValue").asString()).isEqualTo(String.valueOf(changed));
        } finally {
            settings.setSlotDuration(current, developerActor());
        }
    }

    private RequestHeadersSpec<?> setSlotDuration(Session session, int minutes) {
        return as(session, csrf(client.put().uri("/api/schedule/settings/slot-duration")))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("minutes", minutes));
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

    /** Тело успешного ответа {@code 200}. */
    private static JsonNode json(RequestHeadersSpec<?> spec) {
        return json(spec, 200);
    }

    private static JsonNode json(RequestHeadersSpec<?> spec, int status) {
        return Objects.requireNonNull(spec.exchange()
                .expectStatus()
                .isEqualTo(status)
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody());
    }

    private static List<UUID> ids(JsonNode items) {
        return items.values().stream()
                .map(item -> UUID.fromString(item.path("id").asString()))
                .toList();
    }

    private static Map<UUID, JsonNode> byId(JsonNode items) {
        return items.values().stream()
                .collect(
                        Collectors.toMap(item -> UUID.fromString(item.path("id").asString()), Function.identity()));
    }
}
