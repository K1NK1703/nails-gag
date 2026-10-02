package gag.nails.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.schedule.error.TemplateNameTakenException;
import gag.nails.schedule.error.TemplateOverlapException;
import gag.nails.testsupport.ModuleTest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ModuleTest
class DayTemplatesTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);

    @Autowired
    ScheduleTemplates templates;

    @Autowired
    ScheduleService schedule;

    @Autowired
    ScheduleQueries queries;

    @Autowired
    ScheduleSettingsService settings;

    private final Actor master = Actor.master();

    @Test
    void masterCreatesDayTemplatesAndTheFirstOneBecomesDefault() {
        var usual = templates.createDayTemplate(
                new CreateDayTemplate("Обычный день", at("13:30", "09:30", "11:30")), master);
        var shortDay = templates.createDayTemplate(new CreateDayTemplate("Короткий", at("10:00", "12:00")), master);

        assertThat(usual.startTimes())
                .containsExactly(LocalTime.parse("09:30"), LocalTime.parse("11:30"), LocalTime.parse("13:30"));
        assertThat(usual.isDefault()).isTrue();
        assertThat(usual.sortOrder()).isEqualTo(0);
        assertThat(shortDay.isDefault()).isFalse();
        assertThat(shortDay.sortOrder()).isEqualTo(1);
        assertThat(templates.dayTemplates(master))
                .extracting(DayTemplateView::name)
                .containsExactly("Обычный день", "Короткий");
    }

    @Test
    void templateNameMustBeUnique() {
        templates.createDayTemplate(new CreateDayTemplate("Обычный день", at("09:30")), master);

        assertThatThrownBy(
                        () -> templates.createDayTemplate(new CreateDayTemplate("Обычный день", at("12:00")), master))
                .isInstanceOf(TemplateNameTakenException.class);
    }

    @Test
    void overlappingTimesAreRejectedForCurrentDuration() {
        assertThatThrownBy(() ->
                        templates.createDayTemplate(new CreateDayTemplate("Тесный", at("09:30", "10:30")), master))
                .isInstanceOf(TemplateOverlapException.class)
                .satisfies(e -> assertThat(((TemplateOverlapException) e).params())
                        .containsEntry("first", LocalTime.parse("09:30"))
                        .containsEntry("second", LocalTime.parse("10:30"))
                        .containsEntry("durationMinutes", 120));
    }

    @Test
    void duplicateTimesAreRejected() {
        assertThatThrownBy(() ->
                        templates.createDayTemplate(new CreateDayTemplate("Двойной", at("09:30", "09:30")), master))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "startTimes"));
    }

    @Test
    void updateReplacesTimesAndName() {
        var template = templates.createDayTemplate(
                new CreateDayTemplate("Обычный день", at("09:30", "11:30", "13:30")), master);

        var updated = templates.updateDayTemplate(
                template.id(), new UpdateDayTemplate("Будни", at("09:30", "12:00")), master);

        assertThat(updated.id()).isEqualTo(template.id());
        assertThat(updated.startTimes()).containsExactly(LocalTime.parse("09:30"), LocalTime.parse("12:00"));
        assertThat(templates.dayTemplates(master))
                .extracting(DayTemplateView::name)
                .containsExactly("Будни");
    }

    @Test
    void setDefaultMovesTheFlag() {
        templates.createDayTemplate(new CreateDayTemplate("Обычный день", at("09:30")), master);
        var shortDay = templates.createDayTemplate(new CreateDayTemplate("Короткий", at("10:00")), master);

        templates.setDefaultDayTemplate(shortDay.id(), master);

        assertThat(templates.dayTemplates(master))
                .extracting(DayTemplateView::isDefault)
                .containsExactly(false, true);
    }

    @Test
    void deleteRemovesTemplate() {
        var template = templates.createDayTemplate(new CreateDayTemplate("Обычный день", at("09:30")), master);

        templates.deleteDayTemplate(template.id(), master);

        assertThat(templates.dayTemplates(master)).isEmpty();
    }

    @Test
    void applyDayTemplateCreatesSlotsAndSkipsOpenDays() {
        var template = templates.createDayTemplate(new CreateDayTemplate("Обычный день", at("09:30", "11:30")), master);
        var tuesday = MONDAY.plusDays(1);
        var wednesday = MONDAY.plusDays(2);
        schedule.addSlot(tuesday, LocalTime.parse("15:00"), master);

        var result = schedule.applyDayTemplate(template.id(), List.of(MONDAY, tuesday, wednesday), master);

        assertThat(result.created()).hasSize(4);
        assertThat(result.created()).extracting(SlotView::day).containsOnly(MONDAY, wednesday);
        assertThat(result.skipped()).containsExactly(tuesday);
        assertThat(queries.freeSlots(MONDAY, wednesday)).hasSize(5);
    }

    @Test
    void applyRejectsTemplateOverlappingAtCurrentDuration() {
        var template = templates.createDayTemplate(new CreateDayTemplate("Обычный день", at("09:30", "11:30")), master);
        settings.setSlotDuration(150, master);

        assertThatThrownBy(() -> schedule.applyDayTemplate(template.id(), List.of(MONDAY), master))
                .isInstanceOf(TemplateOverlapException.class);
    }

    @Test
    void openDayIsApplyToOneDate() {
        var template = templates.createDayTemplate(new CreateDayTemplate("Обычный день", at("09:30", "11:30")), master);

        var result = schedule.openDay(MONDAY, template.id(), master);

        assertThat(result.created()).extracting(SlotView::day).containsOnly(MONDAY);
        assertThat(result.created()).hasSize(2);
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void applyRequiresDates() {
        var template = templates.createDayTemplate(new CreateDayTemplate("Обычный день", at("09:30")), master);

        assertThatThrownBy(() -> schedule.applyDayTemplate(template.id(), List.of(), master))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "dates"));
    }

    @Test
    void clientCannotManageTemplates() {
        var client = Actor.client(UUID.randomUUID());

        assertThatThrownBy(
                        () -> templates.createDayTemplate(new CreateDayTemplate("Обычный день", at("09:30")), client))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> templates.dayTemplates(client)).isInstanceOf(AccessDeniedException.class);
    }

    private static List<LocalTime> at(String... times) {
        return Stream.of(times).map(LocalTime::parse).toList();
    }
}
