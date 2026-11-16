package gag.nails.notifications.internal.settings;

import gag.nails.notifications.internal.reminder.ReminderSettingsReader;
import gag.nails.notifications.model.ReminderSettings;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Настройки напоминаний для планировщика — единственная строка под разделяемой блокировкой. */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class SharedReminderSettings implements ReminderSettingsReader {

    private final ReminderSettingsRepository settings;

    SharedReminderSettings(ReminderSettingsRepository settings) {
        this.settings = settings;
    }

    @Override
    public ReminderSettings readForShare() {
        return settings.singleForShare().toView();
    }
}
