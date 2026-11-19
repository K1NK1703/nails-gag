package gag.nails.notifications;

import gag.nails.common.error.InternalException;

/**
 * Уборка очереди уведомлений (спека §3.11). Планировщик вызывает её раз в сутки
 * ({@code nails-gag.notifications.jobs.purge-cron}, по умолчанию в 04:15 по времени салона) и один раз после старта
 * приложения; вызвать можно и вручную. Повторный вызов безопасен: убирать уже нечего.
 */
public interface NotificationMaintenance {

    /**
     * Удаляет завершённые сообщения ({@code DISPATCHED} и {@code CANCELLED}), срок отправки которых ({@code due_at})
     * старше срока хранения ({@code nails-gag.notifications.retention-days}), вместе с их доставками, — кроме
     * сообщений, у которых ещё есть неотправленная доставка; и истёкшие ссылки привязки Telegram. Отсчёт — от срока
     * отправки, а не от создания: напоминание создаётся при записи и может быть старше срока хранения ещё до отправки.
     * Одной транзакцией; вызывать вне транзакции.
     *
     * @return сколько сообщений удалено
     * @throws InternalException если вызван внутри транзакции
     */
    int purgeExpired();
}
