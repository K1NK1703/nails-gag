package gag.nails.schedule.internal.template;

import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.ValidationException;
import gag.nails.common.validation.CommandValidator;
import gag.nails.schedule.ScheduleTemplates;
import gag.nails.schedule.command.CreateDayTemplate;
import gag.nails.schedule.command.CreateWeekTemplate;
import gag.nails.schedule.command.SetRotation;
import gag.nails.schedule.command.UpdateDayTemplate;
import gag.nails.schedule.command.UpdateWeekTemplate;
import gag.nails.schedule.error.DayTemplateInUseException;
import gag.nails.schedule.error.DayTemplateNotFoundException;
import gag.nails.schedule.error.TemplateNameTakenException;
import gag.nails.schedule.error.WeekTemplateInUseException;
import gag.nails.schedule.error.WeekTemplateNotFoundException;
import gag.nails.schedule.internal.rotation.RotationRepository;
import gag.nails.schedule.internal.settings.SettingsRepository;
import gag.nails.schedule.model.DayTemplateView;
import gag.nails.schedule.model.RotationView;
import gag.nails.schedule.model.WeekTemplateView;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultScheduleTemplates implements ScheduleTemplates {

    private final DayTemplateRepository dayTemplates;
    private final WeekTemplateRepository weekTemplates;
    private final RotationRepository rotations;
    private final SettingsRepository settings;
    private final CommandValidator validator;

    DefaultScheduleTemplates(
            DayTemplateRepository dayTemplates,
            WeekTemplateRepository weekTemplates,
            RotationRepository rotations,
            SettingsRepository settings,
            CommandValidator validator) {
        this.dayTemplates = dayTemplates;
        this.weekTemplates = weekTemplates;
        this.rotations = rotations;
        this.settings = settings;
        this.validator = validator;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DayTemplateView> dayTemplates(Actor actor) {
        actor.requireRead(Section.SCHEDULE);
        return dayTemplates.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(DayTemplateEntity::toView)
                .toList();
    }

    @Override
    public DayTemplateView createDayTemplate(CreateDayTemplate command, Actor actor) {
        actor.requireWrite(Section.SCHEDULE);
        validator.requireValid(command);
        if (dayTemplates.existsByName(command.name())) {
            throw new TemplateNameTakenException(command.name());
        }
        var template = DayTemplateEntity.create(command.name(), checkedTimes(command.startTimes()), nextSortOrder());
        template.setDefault(dayTemplates.findByDefaultTemplateTrue().isEmpty());
        return dayTemplates.save(template).toView();
    }

    @Override
    public DayTemplateView updateDayTemplate(UUID templateId, UpdateDayTemplate command, Actor actor) {
        actor.requireWrite(Section.SCHEDULE);
        validator.requireValid(command);
        var template = requireDayTemplate(templateId);
        if (dayTemplates.existsByNameAndIdNot(command.name(), templateId)) {
            throw new TemplateNameTakenException(command.name());
        }
        var startTimes = checkedTimes(command.startTimes());
        template.rename(command.name());
        template.setStartTimes(startTimes);
        return template.toView();
    }

    @Override
    public void deleteDayTemplate(UUID templateId, Actor actor) {
        actor.requireWrite(Section.SCHEDULE);
        var template = requireDayTemplate(templateId);
        if (weekTemplates.usesDayTemplate(templateId)) {
            throw new DayTemplateInUseException(templateId);
        }
        dayTemplates.delete(template);
    }

    @Override
    public DayTemplateView setDefaultDayTemplate(UUID templateId, Actor actor) {
        actor.requireWrite(Section.SCHEDULE);
        var template = requireDayTemplate(templateId);
        if (template.isDefault()) {
            return template.toView();
        }
        dayTemplates.findByDefaultTemplateTrue().ifPresent(current -> current.setDefault(false));
        // Частичный уникальный индекс day_template_default_key проверяется на каждом UPDATE:
        // снять флаг с прежнего шаблона надо до того, как поставить его новому.
        dayTemplates.flush();
        template.setDefault(true);
        return template.toView();
    }

    @Override
    @Transactional(readOnly = true)
    public List<WeekTemplateView> weekTemplates(Actor actor) {
        actor.requireRead(Section.SCHEDULE);
        return weekTemplates.findAllByOrderByNameAsc().stream()
                .map(WeekTemplateEntity::toView)
                .toList();
    }

    @Override
    public WeekTemplateView createWeekTemplate(CreateWeekTemplate command, Actor actor) {
        actor.requireWrite(Section.SCHEDULE);
        validator.requireValid(command);
        if (weekTemplates.existsByName(command.name())) {
            throw new TemplateNameTakenException(command.name());
        }
        var days = byWeekday(command.days());
        requireDayTemplatesExist(days.values());
        return weekTemplates
                .save(WeekTemplateEntity.create(command.name(), days))
                .toView();
    }

    @Override
    public WeekTemplateView updateWeekTemplate(UUID templateId, UpdateWeekTemplate command, Actor actor) {
        actor.requireWrite(Section.SCHEDULE);
        validator.requireValid(command);
        var template = requireWeekTemplate(templateId);
        if (weekTemplates.existsByNameAndIdNot(command.name(), templateId)) {
            throw new TemplateNameTakenException(command.name());
        }
        var days = byWeekday(command.days());
        requireDayTemplatesExist(days.values());
        template.rename(command.name());
        template.setDays(days);
        return template.toView();
    }

    @Override
    public void deleteWeekTemplate(UUID templateId, Actor actor) {
        actor.requireWrite(Section.SCHEDULE);
        var template = requireWeekTemplate(templateId);
        if (rotations.usesWeekTemplate(templateId)) {
            throw new WeekTemplateInUseException(templateId);
        }
        weekTemplates.delete(template);
    }

    @Override
    @Transactional(readOnly = true)
    public RotationView rotation(Actor actor) {
        actor.requireRead(Section.SCHEDULE);
        return rotations.single().toView();
    }

    @Override
    public RotationView setRotation(SetRotation command, Actor actor) {
        actor.requireWrite(Section.SCHEDULE);
        validator.requireValid(command);
        if (command.anchorMonday().getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new ValidationException("anchorMonday", "must be a Monday");
        }
        if (command.anchorIndex() >= command.weekTemplateIds().size()) {
            throw new ValidationException("anchorIndex", "must point into weekTemplateIds");
        }
        requireWeekTemplatesExist(command.weekTemplateIds());
        var rotation = rotations.single();
        rotation.set(command.weekTemplateIds(), command.anchorMonday(), command.anchorIndex());
        return rotation.toView();
    }

    private List<LocalTime> checkedTimes(List<LocalTime> startTimes) {
        var sorted = TemplateTimes.normalized(startTimes);
        TemplateTimes.requireNoOverlap(sorted, settings.single().slotDurationMinutes());
        return sorted;
    }

    /** Шаблоны дня из карты рабочих дней должны существовать; {@code ids} — в порядке дней недели. */
    private void requireDayTemplatesExist(Collection<UUID> ids) {
        var found = dayTemplates.findByIds(ids);
        for (var id : ids) {
            if (!found.containsKey(id)) {
                throw new DayTemplateNotFoundException(id);
            }
        }
    }

    /** Шаблоны недели из списка ротации должны существовать; {@code ids} — в порядке списка. */
    private void requireWeekTemplatesExist(Collection<UUID> ids) {
        var found = weekTemplates.findByIds(ids);
        for (var id : ids) {
            if (!found.containsKey(id)) {
                throw new WeekTemplateNotFoundException(id);
            }
        }
    }

    /** Карта команды в порядке дней недели: так первый отсутствующий шаблон дня не зависит от реализации карты. */
    private static Map<DayOfWeek, UUID> byWeekday(Map<DayOfWeek, UUID> days) {
        return days.isEmpty() ? Map.of() : new EnumMap<>(days);
    }

    private DayTemplateEntity requireDayTemplate(UUID templateId) {
        return dayTemplates.findById(templateId).orElseThrow(() -> new DayTemplateNotFoundException(templateId));
    }

    private WeekTemplateEntity requireWeekTemplate(UUID templateId) {
        return weekTemplates.findById(templateId).orElseThrow(() -> new WeekTemplateNotFoundException(templateId));
    }

    private int nextSortOrder() {
        return dayTemplates
                .findFirstByOrderBySortOrderDesc()
                .map(last -> last.sortOrder() + 1)
                .orElse(0);
    }
}
