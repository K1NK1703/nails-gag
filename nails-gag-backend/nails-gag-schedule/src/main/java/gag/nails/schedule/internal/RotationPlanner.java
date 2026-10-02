package gag.nails.schedule.internal;

import gag.nails.common.error.ValidationException;
import gag.nails.schedule.ApplyRotation;
import gag.nails.schedule.DayPlan;
import gag.nails.schedule.DayPlanStatus;
import gag.nails.schedule.RotationPreview;
import gag.nails.schedule.TemplateApplication;
import gag.nails.schedule.WeekChoice;
import gag.nails.schedule.WeekPlan;
import gag.nails.schedule.error.RotationNotConfiguredException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Предпросмотр и применение ротации недель к периоду (спецификация 6.3). */
@Component
class RotationPlanner {

    private final RotationRepository rotations;
    private final WeekTemplateRepository weekTemplates;
    private final DayTemplateRepository dayTemplates;
    private final SettingsRepository settings;
    private final SlotRepository slots;
    private final DayOpener opener;

    RotationPlanner(
            RotationRepository rotations,
            WeekTemplateRepository weekTemplates,
            DayTemplateRepository dayTemplates,
            SettingsRepository settings,
            SlotRepository slots,
            DayOpener opener) {
        this.rotations = rotations;
        this.weekTemplates = weekTemplates;
        this.dayTemplates = dayTemplates;
        this.settings = settings;
        this.slots = slots;
        this.opener = opener;
    }

    RotationPreview preview(LocalDate from, LocalDate to) {
        Periods.requireOrdered(from, to);
        var rotation = requireConfigured();
        var mondays = Periods.mondays(from, to);
        Function<LocalDate, UUID> byRotation =
                monday -> rotation.weekTemplateIds().get(rotation.templateIndexFor(monday));
        var weeks = plan(from, to, mondays, byRotation, settings.single().slotDurationMinutes());
        return new RotationPreview(from, to, weeks);
    }

    TemplateApplication apply(ApplyRotation command) {
        Periods.requireOrdered(command.from(), command.to());
        var rotation = requireConfigured();
        var mondays = Periods.mondays(command.from(), command.to());
        var chosen = chosenTemplates(command.weeks(), mondays, rotation.weekTemplateIds());
        var duration = settings.single().slotDurationMinutes();
        var weeks = plan(command.from(), command.to(), mondays, chosen::get, duration);

        var toOpen = new TreeMap<LocalDate, List<LocalTime>>();
        var skipped = new ArrayList<LocalDate>();
        for (var week : weeks) {
            for (var day : week.days()) {
                switch (day.status()) {
                    case PLANNED -> toOpen.put(day.date(), day.startTimes());
                    case ALREADY_OPEN -> skipped.add(day.date());
                    case DAY_OFF -> {}
                }
            }
        }
        var opened = opener.open(toOpen, duration);

        var lastMonday = mondays.getLast();
        rotation.moveAnchor(lastMonday, rotation.weekTemplateIds().indexOf(chosen.get(lastMonday)));
        return new TemplateApplication(opened.created(), List.copyOf(skipped));
    }

    private RotationEntity requireConfigured() {
        var rotation = rotations.single();
        if (!rotation.isConfigured()) {
            throw new RotationNotConfiguredException();
        }
        return rotation;
    }

    /** План применения: каждая неделя периода с её шаблоном недели и судьбой каждого дня периода. */
    private List<WeekPlan> plan(
            LocalDate from,
            LocalDate to,
            List<LocalDate> mondays,
            Function<LocalDate, UUID> weekTemplateFor,
            int durationMinutes) {
        var weekIds = mondays.stream().map(weekTemplateFor).collect(Collectors.toSet());
        var weeks = weekTemplates.findByIds(weekIds);
        var dayIds = weeks.values().stream()
                .flatMap(week -> week.days().values().stream())
                .collect(Collectors.toSet());
        var days = dayTemplates.findByIds(dayIds);
        days.values().forEach(template -> TemplateTimes.requireNoOverlap(template.startTimes(), durationMinutes));
        var openDays = Set.copyOf(slots.openDays(Periods.days(from, to)));

        var result = new ArrayList<WeekPlan>();
        for (var monday : mondays) {
            var week = weeks.get(weekTemplateFor.apply(monday));
            result.add(new WeekPlan(monday, week.id(), week.name(), planDays(monday, week, from, to, days, openDays)));
        }
        return List.copyOf(result);
    }

    private static List<DayPlan> planDays(
            LocalDate monday,
            WeekTemplateEntity week,
            LocalDate from,
            LocalDate to,
            Map<UUID, DayTemplateEntity> dayTemplates,
            Set<LocalDate> openDays) {
        var days = new ArrayList<DayPlan>();
        for (var date = monday; date.isBefore(monday.plusWeeks(1)); date = date.plusDays(1)) {
            if (date.isBefore(from) || date.isAfter(to)) {
                continue;
            }
            var dayTemplateId = week.dayTemplateFor(date.getDayOfWeek());
            if (dayTemplateId.isEmpty()) {
                days.add(new DayPlan(date, DayPlanStatus.DAY_OFF, null, List.of()));
                continue;
            }
            var startTimes = dayTemplates.get(dayTemplateId.get()).startTimes();
            var status = openDays.contains(date) ? DayPlanStatus.ALREADY_OPEN : DayPlanStatus.PLANNED;
            days.add(new DayPlan(date, status, dayTemplateId.get(), startTimes));
        }
        return List.copyOf(days);
    }

    /** Понедельник → выбранный шаблон недели; план должен покрывать все недели периода ровно по одному разу. */
    private static Map<LocalDate, UUID> chosenTemplates(
            List<WeekChoice> choices, List<LocalDate> mondays, List<UUID> rotationTemplates) {
        var chosen = new HashMap<LocalDate, UUID>();
        for (var choice : choices) {
            if (!rotationTemplates.contains(choice.weekTemplateId())) {
                throw new ValidationException("weeks", "must use week templates of the rotation");
            }
            if (chosen.put(choice.monday(), choice.weekTemplateId()) != null) {
                throw new ValidationException("weeks", "must not repeat a week");
            }
        }
        if (!chosen.keySet().equals(Set.copyOf(mondays))) {
            throw new ValidationException("weeks", "must list every week of the period exactly once");
        }
        return chosen;
    }
}
