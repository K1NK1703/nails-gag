package gag.nails.schedule;

import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.WEDNESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.testsupport.ModuleTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ModuleTest
class ScheduleAccessTest {

    /** Понедельник 5 октября 2026. */
    private static final LocalDate MONDAY_5TH = LocalDate.of(2026, 10, 5);

    @Autowired
    ScheduleService schedule;

    @Autowired
    ScheduleQueries queries;

    @Autowired
    ScheduleTemplates templates;

    @Autowired
    ScheduleSettingsService settings;

    @PersistenceContext
    EntityManager entityManager;

    private final Actor master = Actor.master();

    private UUID dayTemplate;
    private UUID weekTemplate;
    private SlotView slot;

    @BeforeEach
    void seed() {
        dayTemplate = templates
                .createDayTemplate(
                        new CreateDayTemplate("Будни", List.of(LocalTime.of(10, 0), LocalTime.of(13, 0))), master)
                .id();
        weekTemplate = templates
                .createWeekTemplate(
                        new CreateWeekTemplate("Неделя", Map.of(MONDAY, dayTemplate, WEDNESDAY, dayTemplate)), master)
                .id();
        templates.setRotation(new SetRotation(List.of(weekTemplate), MONDAY_5TH, 0), master);
        slot = schedule.addSlot(MONDAY_5TH.plusDays(1), LocalTime.of(9, 0), master);
    }

    @Test
    void readerReadsButDoesNotWrite() {
        var reader = Actor.of(null, null, Map.of(Section.SCHEDULE, AccessLevel.READ));

        assertThat(queries.slotsOfDay(MONDAY_5TH.plusDays(1), reader)).hasSize(1);
        assertThat(queries.slotsOfPeriod(MONDAY_5TH, MONDAY_5TH.plusDays(6), reader))
                .hasSize(1);
        assertThat(templates.dayTemplates(reader)).isNotEmpty();
        assertThat(templates.weekTemplates(reader)).isNotEmpty();
        assertThat(templates.rotation(reader).weekTemplateIds()).containsExactly(weekTemplate);
        assertThat(settings.history(reader)).isEmpty();
        assertThat(schedule.previewRotation(MONDAY_5TH, MONDAY_5TH.plusDays(6), reader)
                        .weeks())
                .hasSize(1);

        assertThatThrownBy(() -> schedule.addSlot(MONDAY_5TH, LocalTime.of(9, 0), reader))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> schedule.moveSlot(slot.id(), LocalTime.of(12, 0), reader))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> schedule.deleteSlot(slot.id(), reader)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> schedule.applyDayTemplate(dayTemplate, List.of(MONDAY_5TH), reader))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> schedule.applyRotation(
                        new ApplyRotation(
                                MONDAY_5TH, MONDAY_5TH.plusDays(6), List.of(new WeekChoice(MONDAY_5TH, weekTemplate))),
                        reader))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> templates.createDayTemplate(
                        new CreateDayTemplate("Короткий", List.of(LocalTime.of(10, 0))), reader))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> templates.updateDayTemplate(
                        dayTemplate, new UpdateDayTemplate("Будни", List.of(LocalTime.of(10, 0))), reader))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> templates.deleteDayTemplate(dayTemplate, reader))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> templates.setDefaultDayTemplate(dayTemplate, reader))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> templates.createWeekTemplate(new CreateWeekTemplate("Другая", Map.of()), reader))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() ->
                        templates.updateWeekTemplate(weekTemplate, new UpdateWeekTemplate("Неделя", Map.of()), reader))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> templates.deleteWeekTemplate(weekTemplate, reader))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> templates.setRotation(new SetRotation(List.of(weekTemplate), MONDAY_5TH, 0), reader))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> settings.setSlotDuration(60, reader)).isInstanceOf(AccessDeniedException.class);

        entityManager.flush();
        entityManager.clear();
        assertThat(queries.slotsOfDay(MONDAY_5TH.plusDays(1), reader)).containsExactly(slot);
        assertThat(queries.slotsOfDay(MONDAY_5TH, reader)).isEmpty();
        assertThat(templates.dayTemplates(reader))
                .extracting(DayTemplateView::id)
                .containsExactly(dayTemplate);
        assertThat(templates.weekTemplates(reader))
                .extracting(WeekTemplateView::id)
                .containsExactly(weekTemplate);
        assertThat(settings.settings().slotDurationMinutes()).isEqualTo(120);
        assertThat(settings.history(reader)).isEmpty();
    }

    @Test
    void writerReadsAndWrites() {
        var writer = Actor.of(null, null, Map.of(Section.SCHEDULE, AccessLevel.WRITE));

        assertThat(queries.slotsOfDay(MONDAY_5TH.plusDays(1), writer)).hasSize(1);
        assertThat(templates.dayTemplates(writer)).isNotEmpty();
        assertThat(templates.rotation(writer).weekTemplateIds()).containsExactly(weekTemplate);
        assertThat(settings.history(writer)).isEmpty();
        assertThatCode(() -> {
                    schedule.moveSlot(slot.id(), LocalTime.of(12, 0), writer);
                    schedule.deleteSlot(slot.id(), writer);
                    schedule.applyDayTemplate(dayTemplate, List.of(MONDAY_5TH.plusDays(2)), writer);
                    schedule.applyRotation(
                            new ApplyRotation(
                                    MONDAY_5TH,
                                    MONDAY_5TH.plusDays(6),
                                    List.of(new WeekChoice(MONDAY_5TH, weekTemplate))),
                            writer);
                    var shortDay = templates.createDayTemplate(
                            new CreateDayTemplate("Короткий", List.of(LocalTime.of(10, 0))), writer);
                    templates.setDefaultDayTemplate(shortDay.id(), writer);
                    templates.deleteDayTemplate(shortDay.id(), writer);
                    settings.setSlotDuration(60, writer);
                })
                .doesNotThrowAnyException();
        assertThat(settings.history(writer)).singleElement().satisfies(change -> {
            assertThat(change.oldValue()).isEqualTo("120");
            assertThat(change.newValue()).isEqualTo("60");
        });
    }

    @Test
    void noAccessMeansNoReading() {
        var nobody = Actor.of(null, null, Map.of());
        var client = Actor.client(UUID.randomUUID());

        for (var actor : List.of(nobody, client)) {
            assertThatThrownBy(() -> queries.slotsOfDay(MONDAY_5TH, actor)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> queries.slotsOfPeriod(MONDAY_5TH, MONDAY_5TH, actor))
                    .isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> templates.dayTemplates(actor)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> templates.weekTemplates(actor)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> templates.rotation(actor)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> settings.history(actor)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> schedule.previewRotation(MONDAY_5TH, MONDAY_5TH.plusDays(6), actor))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void otherSectionDoesNotHelp() {
        var cataloguer = Actor.of(null, null, Map.of(Section.CATALOG, AccessLevel.WRITE));

        assertThatThrownBy(() -> queries.slotsOfDay(MONDAY_5TH, cataloguer)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> templates.dayTemplates(cataloguer)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> schedule.addSlot(MONDAY_5TH, LocalTime.of(9, 0), cataloguer))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> settings.setSlotDuration(60, cataloguer)).isInstanceOf(AccessDeniedException.class);
    }
}
