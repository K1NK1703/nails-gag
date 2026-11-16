package gag.nails.notifications.internal.listen;

import gag.nails.clients.event.ClientErased;
import gag.nails.clients.event.ClientMerged;
import gag.nails.identity.event.AccountDeleted;
import gag.nails.notifications.internal.preference.AccountChannels;
import gag.nails.notifications.internal.queue.MessageQueue;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * События карточек и аккаунтов (спека §3.10). Обычные {@code @EventListener} — синхронно, в транзакции источника, как
 * требуют контракты {@link ClientErased}, {@link ClientMerged} и {@link AccountDeleted}: изменения очереди фиксируются
 * вместе с обезличиванием, объединением или удалением, а сбой здесь откатывает всю операцию. Вне транзакции события не
 * публикуются ({@code MANDATORY}). Работа — несколько {@code UPDATE}/{@code DELETE} в своей схеме по частичным
 * индексам неотправленных сообщений, без чтения сущностей и без {@code clearAutomatically}: управляемые сущности
 * источника не отсоединяются.
 *
 * <p>Сообщение, которое событие записи поставит в очередь уже после обезличивания или объединения (слушатель событий
 * записи асинхронный), безвредно: у обезличенной карточки нет почты и аккаунта, удалённой карточки нет — при отправке
 * у такого сообщения не найдётся каналов.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class AccountEventsListener {

    private final MessageQueue queue;
    private final AccountChannels channels;

    AccountEventsListener(MessageQueue queue, AccountChannels channels) {
        this.queue = queue;
        this.channels = channels;
    }

    /** Карточка обезличена: писать ей больше некуда — неотправленные сообщения отменяются. */
    @EventListener
    void on(ClientErased event) {
        queue.cancelPendingOfClient(event.clientId());
    }

    /** Карточки объединены: неотправленные сообщения удалённой переходят оставшейся. */
    @EventListener
    void on(ClientMerged event) {
        queue.movePending(event.removedClientId(), event.survivorClientId());
    }

    /**
     * Аккаунт удалён (владельцем или при слиянии аккаунтов): его каналы забываются, неотправленные мастерские
     * сообщения отменяются. Сообщения его карточки обрабатывает {@link ClientErased} (удаление владельцем) или
     * {@link ClientMerged} (слияние).
     */
    @EventListener
    void on(AccountDeleted event) {
        channels.forget(event.accountId());
        queue.cancelPendingOfAccount(event.accountId());
    }
}
