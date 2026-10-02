package gag.nails.schedule;

import static gag.nails.schedule.DayPlanStatus.ALREADY_OPEN;
import static gag.nails.schedule.DayPlanStatus.DAY_OFF;
import static gag.nails.schedule.DayPlanStatus.PLANNED;
import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.THURSDAY;
import static java.time.DayOfWeek.TUESDAY;
import static java.time.DayOfWeek.WEDNESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.ValidationException;
import gag.nails.schedule.error.RotationNotConfiguredException;
import gag.nails.schedule.error.TemplateOverlapException;
import gag.nails.testsupport.ModuleTest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ModuleTest
class RotationTest {

    /** Понедельник 5 октября 2026. */
    private static final LocalDate ANCHOR = LocalDate.of(2026, 10, 5);

    @Autowired
    ScheduleTemplates templates;

    @Autowired
    ScheduleService schedule;

    @Autowired
    ScheduleQueries queries;

    @Autowired
    ScheduleSettingsService settings;

    private final Actor master = Actor.master();

    private UUID weekdays;
    private UUID shortDay;
    private UUID weekA;
    private UUID weekB;

    @Test
    void previewAlternatesWeekTemplatesFromAnchor() {
        configureRotation();

        var preview = schedule.previewRotation(ANCHOR, ANCHOR.plusDays(34), master);

        assertThat(preview.weeks())
                .extracting(WeekPlan::weekTemplateId)
                .containsExactly(weekA, weekB, weekA, weekB, weekA);
        assertThat(preview.weeks())
                .extracting(WeekPlan::monday)
                .containsExactly(
                        ANCHOR, ANCHOR.plusWeeks(1), ANCHOR.plusWeeks(2), ANCHOR.plusWeeks(3), ANCHOR.plusWeeks(4));
        var firstWeek = preview.weeks().getFirst();
        assertThat(firstWeek.weekTemplateName()).isEqualTo("Неделя А");
        assertThat(firstWeek.days())
                .extracting(DayPlan::status)
                .containsExactly(PLANNED, PLANNED, PLANNED, PLANNED, PLANNED, DAY_OFF, DAY_OFF);
        assertThat(firstWeek.days().getFirst().startTimes()).hasSize(5);
        var secondWeek = preview.weeks().get(1);
        assertThat(secondWeek.days())
                .extracting(DayPlan::status)
                .containsExactly(DAY_OFF, PLANNED, DAY_OFF, PLANNED, DAY_OFF, PLANNED, DAY_OFF);
        assertThat(secondWeek.days().get(5).dayTemplateId()).isEqualTo(shortDay);
    }

    @Test
    void previewCountsBackwardsFromAnchor() {
        configureRotation();

        var preview = schedule.previewRotation(ANCHOR.minusWeeks(1), ANCHOR.minusDays(1), master);

        assertThat(preview.weeks()).extracting(WeekPlan::weekTemplateId).containsExactly(weekB);
    }

    @Test
    void previewMarksAlreadyOpenDays() {
        configureRotation();
        schedule.addSlot(ANCHOR.plusDays(1), LocalTime.parse("09:00"), master);

        var preview = schedule.previewRotation(ANCHOR, ANCHOR.plusDays(6), master);

        var tuesday = preview.weeks().getFirst().days().get(1);
        assertThat(tuesday.status()).isEqualTo(ALREADY_OPEN);
        assertThat(tuesday.startTimes()).hasSize(5);
    }

    @Test
    void previewClipsWeekToPeriod() {
        configureRotation();

        var preview = schedule.previewRotation(ANCHOR.plusDays(2), ANCHOR.plusDays(3), master);

        assertThat(preview.weeks()).hasSize(1);
        assertThat(preview.weeks().getFirst().days())
                .extracting(DayPlan::date)
                .containsExactly(ANCHOR.plusDays(2), ANCHOR.plusDays(3));
    }

    @Test
    void previewRequiresConfiguredRotation() {
        assertThatThrownBy(() -> schedule.previewRotation(ANCHOR, ANCHOR.plusDays(6), master))
                .isInstanceOf(RotationNotConfiguredException.class);
    }

    @Test
    void applyCreatesSlotsSkipsOpenDaysAndMovesAnchor() {
        configureRotation();
        schedule.addSlot(ANCHOR.plusDays(1), LocalTime.parse("09:00"), master);
        var preview = schedule.previewRotation(ANCHOR, ANCHOR.plusDays(13), master);
        assertThat(preview.weeks()).extracting(WeekPlan::weekTemplateId).containsExactly(weekA, weekB);
        var swapped = List.of(new WeekChoice(ANCHOR, weekB), new WeekChoice(ANCHOR.plusWeeks(1), weekA));

        var result = schedule.applyRotation(new ApplyRotation(ANCHOR, ANCHOR.plusDays(13), swapped), master);

        // неделя Б (5–11 октября): вт пропущен, чт 5 слотов, сб 2; неделя А (12–18 октября): пн–пт по 5
        assertThat(result.created()).hasSize(32);
        assertThat(result.skipped()).containsExactly(ANCHOR.plusDays(1));
        assertThat(queries.freeSlots(ANCHOR, ANCHOR.plusDays(13))).hasSize(33);
        assertThat(templates.rotation(master))
                .isEqualTo(new RotationView(List.of(weekA, weekB), ANCHOR.plusWeeks(1), 0));
        var next = schedule.previewRotation(
                ANCHOR.plusWeeks(2), ANCHOR.plusWeeks(2).plusDays(6), master);
        assertThat(next.weeks()).extracting(WeekPlan::weekTemplateId).containsExactly(weekB);
    }

    @Test
    void applyRejectsWeeksThatDoNotMatchPeriod() {
        configureRotation();
        var incomplete = List.of(new WeekChoice(ANCHOR, weekA));

        assertThatThrownBy(() ->
                        schedule.applyRotation(new ApplyRotation(ANCHOR, ANCHOR.plusDays(13), incomplete), master))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "weeks"));
    }

    @Test
    void applyRejectsWeekTemplateOutsideRotation() {
        configureRotation();
        var weekC = templates
                .createWeekTemplate(new CreateWeekTemplate("Неделя В", Map.of(MONDAY, weekdays)), master)
                .id();

        assertThatThrownBy(() -> schedule.applyRotation(
                        new ApplyRotation(ANCHOR, ANCHOR.plusDays(6), List.of(new WeekChoice(ANCHOR, weekC))), master))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "weeks"));
    }

    @Test
    void previewRejectsTemplateOverlappingAtCurrentDuration() {
        configureRotation();
        settings.setSlotDuration(150, master);

        assertThatThrownBy(() -> schedule.previewRotation(ANCHOR, ANCHOR.plusDays(6), master))
                .isInstanceOf(TemplateOverlapException.class);
    }

    /**
     * «Будни» — пять слотов с 09:30, «Короткий» — два. Неделя А: пн–пт по «Будням»; неделя Б: вт и чт по
     * «Будням», сб по «Короткому». Ротация [А, Б], якорь — понедельник 5 октября на неделе А.
     */
    private void configureRotation() {
        weekdays = templates
                .createDayTemplate(
                        new CreateDayTemplate("Будни", at("09:30", "11:30", "13:30", "15:30", "17:30")), master)
                .id();
        shortDay = templates
                .createDayTemplate(new CreateDayTemplate("Короткий", at("10:00", "12:00")), master)
                .id();
        weekA = templates
                .createWeekTemplate(
                        new CreateWeekTemplate(
                                "Неделя А",
                                Map.of(
                                        MONDAY, weekdays,
                                        TUESDAY, weekdays,
                                        WEDNESDAY, weekdays,
                                        THURSDAY, weekdays,
                                        FRIDAY, weekdays)),
                        master)
                .id();
        weekB = templates
                .createWeekTemplate(
                        new CreateWeekTemplate(
                                "Неделя Б", Map.of(TUESDAY, weekdays, THURSDAY, weekdays, SATURDAY, shortDay)),
                        master)
                .id();
        templates.setRotation(new SetRotation(List.of(weekA, weekB), ANCHOR, 0), master);
    }

    private static List<LocalTime> at(String... times) {
        return Stream.of(times).map(LocalTime::parse).toList();
    }
}
