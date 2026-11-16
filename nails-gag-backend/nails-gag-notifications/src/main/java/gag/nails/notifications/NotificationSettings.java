package gag.nails.notifications;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.common.paging.Paged;
import gag.nails.common.paging.Paging;
import gag.nails.notifications.command.DeliveryFilter;
import gag.nails.notifications.command.UpdateReminderSettings;
import gag.nails.notifications.model.DeliveryView;
import gag.nails.notifications.model.ReminderSettings;
import gag.nails.notifications.model.ReminderSettingsChange;
import java.util.List;

/**
 * Настройки напоминаний и журнал доставок. Чтение — уровень чтения раздела «Уведомления», изменение — уровень
 * изменения ({@link AccessDeniedException}).
 */
public interface NotificationSettings {

    /**
     * Текущие настройки напоминаний.
     *
     * @throws AccessDeniedException у актора нет чтения раздела «Уведомления»
     */
    ReminderSettings settings(Actor actor);

    /**
     * Задаёт обе настройки сразу; каждое изменившееся поле пишется в журнал с аккаунтом актора. Если что-то
     * изменилось, в той же транзакции неотправленные напоминания всех предстоящих активных записей рассчитываются
     * заново по новым настройкам: выключенное напоминание уходит из очереди, включённое — ставится. Перепланирование
     * отдельной записи по её событию, начатое во время смены, ждёт её фиксации и считает уже по новым настройкам.
     *
     * @throws ValidationException ({@code dayBeforeAt}) время не в целых минутах или в тихих часах;
     *     ({@code hoursBefore}) вне 1..24
     * @throws AccessDeniedException у актора нет изменения раздела «Уведомления»
     */
    ReminderSettings update(UpdateReminderSettings command, Actor actor);

    /**
     * Журнал изменений настроек напоминаний, новые первыми.
     *
     * @throws AccessDeniedException у актора нет чтения раздела «Уведомления»
     */
    List<ReminderSettingsChange> history(Actor actor);

    /**
     * Журнал доставок постранично: новые по времени отправки сообщения первыми, при равном — по id доставки;
     * {@code total} — все отобранные.
     *
     * @throws AccessDeniedException у актора нет чтения раздела «Уведомления»
     */
    Paged<DeliveryView> deliveries(DeliveryFilter filter, Paging paging, Actor actor);
}
