package gag.nails.notifications.internal.listen;

import gag.nails.clients.event.ClientErased;
import gag.nails.clients.event.ClientMerged;
import gag.nails.identity.event.AccountDeleted;
import gag.nails.notifications.internal.preference.AccountChannels;
import gag.nails.notifications.internal.queue.MessageQueue;
import gag.nails.notifications.internal.reminder.ReminderSettingsReader;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * События карточек и аккаунтов (спека §3.10). Обычные {@code @EventListener} — синхронно, в транзакции источника, как
 * требуют контракты {@link ClientErased}, {@link ClientMerged} и {@link AccountDeleted}: изменения очереди фиксируются
 * вместе с обезличиванием, объединением или удалением, а сбой здесь откатывает всю операцию. Вне транзакции события не
 * публикуются ({@code MANDATORY}). Работа — несколько {@code UPDATE}/{@code DELETE} в своей схеме запросами JDBC
 * (сообщения — по частичным индексам неотправленных): контекст персистентности источника не сбрасывается и не
 * очищается, его управляемые сущности не отсоединяются.
 *
 * <p>Первым делом каждый обработчик берёт строку настроек напоминаний на чтение ({@code FOR SHARE},
 * {@link ReminderSettingsReader}) — так же, как слушатели событий записей, до строк очереди: смена настроек,
 * перепланирующая напоминания всех предстоящих записей, иначе блокировала бы с обработчиком строки очереди накрест, а
 * её напоминания, поставленные во время обезличивания, остались бы неотправленными у обезличенной карточки. Строки
 * своих модулей (карточку, записи) источник берёт раньше, но смена настроек их не ждёт — встречного порядка нет;
 * порядок блокировок очереди — в {@code ReminderPlanner}. <b>Не убирать:</b> блокировка строк очереди в порядке id
 * ({@link MessageQueue}) взаимную блокировку со сменой настроек не предотвращает — смена меняет строки очереди
 * несколькими запросами, по записи за раз, и без строки настроек обезличивание или объединение встаёт с ней накрест
 * (PostgreSQL 40P01).
 *
 * <p>Сообщение, которое событие записи поставит в очередь уже после обезличивания или объединения (слушатель событий
 * записи асинхронный), безвредно: у обезличенной карточки нет почты и аккаунта, удалённой карточки нет — при отправке
 * у такого сообщения не найдётся каналов.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class AccountEventsListener {

    private final ReminderSettingsReader reminderSettings;
    private final MessageQueue queue;
    private final AccountChannels channels;

    AccountEventsListener(ReminderSettingsReader reminderSettings, MessageQueue queue, AccountChannels channels) {
        this.reminderSettings = reminderSettings;
        this.queue = queue;
        this.channels = channels;
    }

    /** Карточка обезличена: писать ей больше некуда — неотправленные сообщения отменяются. */
    @EventListener
    void on(ClientErased event) {
        reminderSettings.readForShare();
        queue.cancelPendingOfClient(event.clientId());
    }

    /** Карточки объединены: неотправленные сообщения удалённой переходят оставшейся. */
    @EventListener
    void on(ClientMerged event) {
        reminderSettings.readForShare();
        queue.movePending(event.removedClientId(), event.survivorClientId());
    }

    /**
     * Аккаунт удалён (владельцем или при слиянии аккаунтов): его каналы забываются, неотправленные мастерские
     * сообщения отменяются. Сообщения его карточки обрабатывает {@link ClientErased} (удаление владельцем) или
     * {@link ClientMerged} (слияние).
     */
    @EventListener
    void on(AccountDeleted event) {
        reminderSettings.readForShare();
        channels.forget(event.accountId());
        queue.cancelPendingOfAccount(event.accountId());
    }
}
