package gag.nails.schedule;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.THURSDAY;
import static java.time.DayOfWeek.TUESDAY;
import static java.time.DayOfWeek.WEDNESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.schedule.error.DayTemplateInUseException;
import gag.nails.schedule.error.DayTemplateNotFoundException;
import gag.nails.schedule.error.TemplateNameTakenException;
import gag.nails.schedule.error.WeekTemplateInUseException;
import gag.nails.schedule.error.WeekTemplateNotFoundException;
import gag.nails.testsupport.ModuleTest;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ModuleTest
class WeekTemplatesTest {

    private static final LocalDate ANCHOR = LocalDate.of(2026, 10, 5);

    @Autowired
    ScheduleTemplates templates;

    private final Actor master = Actor.master();

    @Test
    void masterCreatesWeekTemplateWithWorkingDays() {
        var usual = dayTemplate("Обычный день");

        var week = templates.createWeekTemplate(
                new CreateWeekTemplate("Неделя А", workdays(usual, MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)),
                master);

        assertThat(week.days()).containsOnlyKeys(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY);
        assertThat(week.days()).containsEntry(FRIDAY, usual);
        assertThat(templates.weekTemplates(master))
                .extracting(WeekTemplateView::name)
                .containsExactly("Неделя А");
    }

    @Test
    void weekTemplateRequiresExistingDayTemplates() {
        assertThatThrownBy(() -> templates.createWeekTemplate(
                        new CreateWeekTemplate("Неделя А", Map.of(MONDAY, UUID.randomUUID())), master))
                .isInstanceOf(DayTemplateNotFoundException.class);
    }

    @Test
    void weekTemplateNameMustBeUnique() {
        var usual = dayTemplate("Обычный день");
        templates.createWeekTemplate(new CreateWeekTemplate("Неделя А", workdays(usual, MONDAY)), master);

        assertThatThrownBy(() -> templates.createWeekTemplate(
                        new CreateWeekTemplate("Неделя А", workdays(usual, TUESDAY)), master))
                .isInstanceOf(TemplateNameTakenException.class);
    }

    @Test
    void updateReplacesWorkingDaysAndName() {
        var usual = dayTemplate("Обычный день");
        var shortDay = dayTemplate("Короткий");
        var week = templates.createWeekTemplate(
                new CreateWeekTemplate("Неделя А", workdays(usual, MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)),
                master);
        var days = workdays(usual, TUESDAY, THURSDAY);
        days.put(SATURDAY, shortDay);

        var updated = templates.updateWeekTemplate(week.id(), new UpdateWeekTemplate("Неделя Б", days), master);

        assertThat(updated.days()).containsOnlyKeys(TUESDAY, THURSDAY, SATURDAY);
        assertThat(updated.days()).containsEntry(SATURDAY, shortDay);
        assertThat(templates.weekTemplates(master))
                .extracting(WeekTemplateView::name)
                .containsExactly("Неделя Б");
    }

    @Test
    void dayTemplateUsedByWeekCannotBeDeleted() {
        var usual = dayTemplate("Обычный день");
        templates.createWeekTemplate(new CreateWeekTemplate("Неделя А", workdays(usual, MONDAY)), master);

        assertThatThrownBy(() -> templates.deleteDayTemplate(usual, master))
                .isInstanceOf(DayTemplateInUseException.class);
    }

    @Test
    void rotationIsEmptyUntilConfigured() {
        var rotation = templates.rotation(master);

        assertThat(rotation.weekTemplateIds()).isEmpty();
        assertThat(rotation.anchorMonday()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(rotation.anchorIndex()).isZero();
    }

    @Test
    void masterSetsRotationOfTwoWeeks() {
        var usual = dayTemplate("Обычный день");
        var a = weekTemplate("Неделя А", usual);
        var b = weekTemplate("Неделя Б", usual);

        var rotation = templates.setRotation(new SetRotation(List.of(a, b), ANCHOR, 1), master);

        assertThat(rotation).isEqualTo(new RotationView(List.of(a, b), ANCHOR, 1));
        assertThat(templates.rotation(master)).isEqualTo(rotation);
    }

    @Test
    void rotationAnchorMustBeMonday() {
        var a = weekTemplate("Неделя А", dayTemplate("Обычный день"));

        assertThatThrownBy(() -> templates.setRotation(new SetRotation(List.of(a), ANCHOR.plusDays(1), 0), master))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "anchorMonday"));
    }

    @Test
    void rotationAnchorIndexMustPointIntoList() {
        var a = weekTemplate("Неделя А", dayTemplate("Обычный день"));

        assertThatThrownBy(() -> templates.setRotation(new SetRotation(List.of(a), ANCHOR, 1), master))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "anchorIndex"));
    }

    @Test
    void rotationRequiresExistingWeekTemplates() {
        assertThatThrownBy(() -> templates.setRotation(new SetRotation(List.of(UUID.randomUUID()), ANCHOR, 0), master))
                .isInstanceOf(WeekTemplateNotFoundException.class);
    }

    @Test
    void weekTemplateInRotationCannotBeDeleted() {
        var a = weekTemplate("Неделя А", dayTemplate("Обычный день"));
        templates.setRotation(new SetRotation(List.of(a), ANCHOR, 0), master);

        assertThatThrownBy(() -> templates.deleteWeekTemplate(a, master))
                .isInstanceOf(WeekTemplateInUseException.class);
    }

    @Test
    void clientCannotManageWeekTemplatesOrRotation() {
        var client = Actor.client(UUID.randomUUID());

        assertThatThrownBy(() -> templates.createWeekTemplate(new CreateWeekTemplate("Неделя А", Map.of()), client))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> templates.rotation(client)).isInstanceOf(AccessDeniedException.class);
    }

    private UUID dayTemplate(String name) {
        var times = List.of(LocalTime.parse("09:30"), LocalTime.parse("11:30"));
        return templates
                .createDayTemplate(new CreateDayTemplate(name, times), master)
                .id();
    }

    private UUID weekTemplate(String name, UUID dayTemplateId) {
        return templates
                .createWeekTemplate(new CreateWeekTemplate(name, workdays(dayTemplateId, MONDAY, WEDNESDAY)), master)
                .id();
    }

    private static Map<DayOfWeek, UUID> workdays(UUID dayTemplateId, DayOfWeek... days) {
        var map = new EnumMap<DayOfWeek, UUID>(DayOfWeek.class);
        for (var day : days) {
            map.put(day, dayTemplateId);
        }
        return map;
    }
}
