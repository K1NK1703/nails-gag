package gag.nails.schedule.internal.rotation;

import gag.nails.common.error.ValidationException;
import gag.nails.schedule.command.ApplyRotation;
import gag.nails.schedule.error.RotationNotConfiguredException;
import gag.nails.schedule.internal.settings.SettingsRepository;
import gag.nails.schedule.internal.slot.DayOpener;
import gag.nails.schedule.internal.slot.Periods;
import gag.nails.schedule.internal.slot.SlotRepository;
import gag.nails.schedule.internal.template.DayTemplateEntity;
import gag.nails.schedule.internal.template.DayTemplateRepository;
import gag.nails.schedule.internal.template.TemplateTimes;
import gag.nails.schedule.internal.template.WeekTemplateEntity;
import gag.nails.schedule.internal.template.WeekTemplateRepository;
import gag.nails.schedule.model.DayPlan;
import gag.nails.schedule.model.DayPlanStatus;
import gag.nails.schedule.model.RotationPreview;
import gag.nails.schedule.model.TemplateApplication;
import gag.nails.schedule.model.WeekChoice;
import gag.nails.schedule.model.WeekPlan;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Предпросмотр и применение ротации недель к периоду (спецификация 6.3). */
@Component
public class RotationPlanner {

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

    public RotationPreview preview(LocalDate from, LocalDate to) {
        Periods.requireOrdered(from, to);
        var rotation = requireConfigured();
        var ids = rotation.weekTemplateIds();
        var byRotation = new TreeMap<LocalDate, UUID>();
        for (var monday : Periods.mondays(from, to)) {
            byRotation.put(monday, ids.get(rotation.templateIndexFor(monday)));
        }
        var weeks = plan(from, to, byRotation, settings.single().slotDurationMinutes());
        return new RotationPreview(from, to, weeks);
    }

    public TemplateApplication apply(ApplyRotation command) {
        Periods.requireOrdered(command.from(), command.to());
        var rotation = requireConfigured();
        var mondays = Periods.mondays(command.from(), command.to());
        var chosen = chosenTemplates(command.weeks(), mondays, rotation.weekTemplateIds());
        var duration = settings.single().slotDurationMinutes();
        var weeks = plan(command.from(), command.to(), chosen, duration);

        // Пропуск уже открытых дней — правило DayOpener: он же возвращает их в skipped.
        var toOpen = new TreeMap<LocalDate, List<LocalTime>>();
        for (var week : weeks) {
            for (var day : week.days()) {
                if (day.status() != DayPlanStatus.DAY_OFF) {
                    toOpen.put(day.date(), day.startTimes());
                }
            }
        }
        var lastMonday = chosen.lastKey();
        rotation.moveAnchor(lastMonday, chosen.get(lastMonday));
        return opener.open(toOpen, duration);
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
            LocalDate from, LocalDate to, SortedMap<LocalDate, UUID> weekTemplateByMonday, int durationMinutes) {
        // FK гарантирует наличие: шаблон нельзя удалить, пока он в ротации / в шаблоне недели.
        var weeks = weekTemplates.findByIds(Set.copyOf(weekTemplateByMonday.values()));
        var dayIds = weeks.values().stream()
                .flatMap(week -> week.days().values().stream())
                .collect(Collectors.toSet());
        var days = dayTemplates.findByIds(dayIds);
        days.values().forEach(template -> TemplateTimes.requireNoOverlap(template.startTimes(), durationMinutes));
        var openDays = Set.copyOf(slots.openDaysBetween(from, to));

        var result = new ArrayList<WeekPlan>();
        weekTemplateByMonday.forEach((monday, weekTemplateId) -> {
            var week = weeks.get(weekTemplateId);
            var dates = Periods.days(max(monday, from), min(monday.plusDays(6), to));
            result.add(new WeekPlan(monday, week.id(), week.name(), planDays(dates, week, days, openDays)));
        });
        return List.copyOf(result);
    }

    private static List<DayPlan> planDays(
            List<LocalDate> dates,
            WeekTemplateEntity week,
            Map<UUID, DayTemplateEntity> dayTemplates,
            Set<LocalDate> openDays) {
        var days = new ArrayList<DayPlan>();
        for (var date : dates) {
            var dayTemplateId = week.dayTemplateFor(date.getDayOfWeek());
            if (dayTemplateId.isEmpty()) {
                days.add(new DayPlan(date, DayPlanStatus.DAY_OFF, null, List.of()));
            } else {
                var startTimes = dayTemplates.get(dayTemplateId.get()).startTimes();
                var status = openDays.contains(date) ? DayPlanStatus.ALREADY_OPEN : DayPlanStatus.PLANNED;
                days.add(new DayPlan(date, status, dayTemplateId.get(), startTimes));
            }
        }
        return List.copyOf(days);
    }

    /** Понедельник → выбранный шаблон недели; план должен покрывать все недели периода ровно по одному разу. */
    private static TreeMap<LocalDate, UUID> chosenTemplates(
            List<WeekChoice> choices, List<LocalDate> mondays, List<UUID> rotationTemplates) {
        var chosen = new TreeMap<LocalDate, UUID>();
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

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
