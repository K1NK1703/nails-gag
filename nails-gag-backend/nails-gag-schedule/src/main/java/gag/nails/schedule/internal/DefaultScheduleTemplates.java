package gag.nails.schedule.internal;

import gag.nails.common.actor.Actor;
import gag.nails.common.validation.CommandValidator;
import gag.nails.schedule.CreateDayTemplate;
import gag.nails.schedule.DayTemplateView;
import gag.nails.schedule.ScheduleTemplates;
import gag.nails.schedule.UpdateDayTemplate;
import gag.nails.schedule.error.DayTemplateInUseException;
import gag.nails.schedule.error.DayTemplateNotFoundException;
import gag.nails.schedule.error.TemplateNameTakenException;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultScheduleTemplates implements ScheduleTemplates {

    private final DayTemplateRepository dayTemplates;
    private final WeekTemplateRepository weekTemplates;
    private final SettingsRepository settings;
    private final CommandValidator validator;

    DefaultScheduleTemplates(
            DayTemplateRepository dayTemplates,
            WeekTemplateRepository weekTemplates,
            SettingsRepository settings,
            CommandValidator validator) {
        this.dayTemplates = dayTemplates;
        this.weekTemplates = weekTemplates;
        this.settings = settings;
        this.validator = validator;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DayTemplateView> dayTemplates(Actor actor) {
        actor.requireMaster();
        return dayTemplates.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(DayTemplateEntity::toView)
                .toList();
    }

    @Override
    public DayTemplateView createDayTemplate(CreateDayTemplate command, Actor actor) {
        actor.requireMaster();
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
        actor.requireMaster();
        validator.requireValid(command);
        var template = requireDayTemplate(templateId);
        if (dayTemplates.existsByNameAndIdNot(command.name(), templateId)) {
            throw new TemplateNameTakenException(command.name());
        }
        template.rename(command.name());
        template.setStartTimes(checkedTimes(command.startTimes()));
        return template.toView();
    }

    @Override
    public void deleteDayTemplate(UUID templateId, Actor actor) {
        actor.requireMaster();
        var template = requireDayTemplate(templateId);
        if (weekTemplates.usesDayTemplate(templateId)) {
            throw new DayTemplateInUseException(templateId);
        }
        dayTemplates.delete(template);
    }

    @Override
    public DayTemplateView setDefaultDayTemplate(UUID templateId, Actor actor) {
        actor.requireMaster();
        var template = requireDayTemplate(templateId);
        dayTemplates.findByDefaultTemplateTrue().ifPresent(current -> current.setDefault(false));
        // Частичный уникальный индекс day_template_default_key проверяется на каждом UPDATE:
        // снять флаг с прежнего шаблона надо до того, как поставить его новому.
        dayTemplates.flush();
        template.setDefault(true);
        return template.toView();
    }

    private List<LocalTime> checkedTimes(List<LocalTime> startTimes) {
        var sorted = TemplateTimes.normalized(startTimes);
        TemplateTimes.requireNoOverlap(sorted, settings.single().slotDurationMinutes());
        return sorted;
    }

    private DayTemplateEntity requireDayTemplate(UUID templateId) {
        return dayTemplates.findById(templateId).orElseThrow(() -> new DayTemplateNotFoundException(templateId));
    }

    private int nextSortOrder() {
        return dayTemplates
                .findFirstByOrderBySortOrderDesc()
                .map(last -> last.sortOrder() + 1)
                .orElse(0);
    }
}
