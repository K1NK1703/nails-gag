package gag.nails.notifications.internal.settings;

import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.ValidationException;
import gag.nails.common.paging.Paged;
import gag.nails.common.paging.Paging;
import gag.nails.common.validation.CommandValidator;
import gag.nails.notifications.NotificationSettings;
import gag.nails.notifications.command.DeliveryFilter;
import gag.nails.notifications.command.UpdateReminderSettings;
import gag.nails.notifications.internal.NotificationsProperties;
import gag.nails.notifications.internal.reminder.ReminderPlanner;
import gag.nails.notifications.model.DeliveryView;
import gag.nails.notifications.model.ReminderSettings;
import gag.nails.notifications.model.ReminderSettingsChange;
import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultNotificationSettings implements NotificationSettings {

    /** Время в журнале — {@code ЧЧ:ММ}, как его задаёт мастер. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final ReminderSettingsRepository settings;
    private final ReminderSettingsChangeRepository changes;
    private final DeliveryJournal deliveries;
    private final ReminderPlanner reminders;
    private final CommandValidator validator;
    private final NotificationsProperties properties;
    private final Clock clock;

    DefaultNotificationSettings(
            ReminderSettingsRepository settings,
            ReminderSettingsChangeRepository changes,
            DeliveryJournal deliveries,
            ReminderPlanner reminders,
            CommandValidator validator,
            NotificationsProperties properties,
            Clock clock) {
        this.settings = settings;
        this.changes = changes;
        this.deliveries = deliveries;
        this.reminders = reminders;
        this.validator = validator;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ReminderSettings settings(Actor actor) {
        actor.requireRead(Section.NOTIFICATIONS);
        return settings.single().toView();
    }

    @Override
    public ReminderSettings update(UpdateReminderSettings command, Actor actor) {
        actor.requireWrite(Section.NOTIFICATIONS);
        validator.requireValid(command);
        requireDayBeforeAt(command.dayBeforeAt());
        var current = settings.singleForUpdate();
        var before = byColumn(current.toView());
        var now = clock.instant();
        current.apply(command, now);
        var updated = current.toView();
        var after = byColumn(updated);
        after.forEach((field, value) -> {
            var old = before.get(field);
            if (!Objects.equals(old, value)) {
                changes.save(new ReminderSettingsChangeEntity(field, old, value, actor.accountId(), now));
            }
        });
        if (!after.equals(before)) {
            // В этой же транзакции, под строкой настроек на запись: слушатели событий записей ждут её фиксации.
            reminders.replanUpcoming(updated);
        }
        return updated;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReminderSettingsChange> history(Actor actor) {
        actor.requireRead(Section.NOTIFICATIONS);
        return changes.findAllByOrderByChangedAtDescIdDesc().stream()
                .map(ReminderSettingsChangeEntity::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Paged<DeliveryView> deliveries(DeliveryFilter filter, Paging paging, Actor actor) {
        actor.requireRead(Section.NOTIFICATIONS);
        return deliveries.page(filter, paging);
    }

    /** Накануне визита напоминаем в целых минутах и не в тихие часы: клиентку ночью не будим. */
    private void requireDayBeforeAt(@Nullable LocalTime dayBeforeAt) {
        if (dayBeforeAt == null) {
            return;
        }
        if (dayBeforeAt.getSecond() != 0 || dayBeforeAt.getNano() != 0) {
            throw new ValidationException("dayBeforeAt", "must be whole minutes");
        }
        var quiet = properties.quietHours();
        if (quiet.contains(dayBeforeAt)) {
            throw new ValidationException(
                    "dayBeforeAt",
                    "must be outside quiet hours " + TIME.format(quiet.from()) + "-" + TIME.format(quiet.to()));
        }
    }

    /** Значения текстом по именам колонок — так поле называется в журнале; {@code null} — выключено. */
    private static Map<String, @Nullable String> byColumn(ReminderSettings view) {
        var values = new LinkedHashMap<String, @Nullable String>();
        values.put("reminder_day_before_at", view.dayBeforeAt() == null ? null : TIME.format(view.dayBeforeAt()));
        values.put("reminder_hours_before", view.hoursBefore() == null ? null : String.valueOf(view.hoursBefore()));
        return values;
    }
}
