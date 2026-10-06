package gag.nails.identity.internal;

import gag.nails.clients.event.ClientMerged;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Объединение клиентов, у обоих из которых был аккаунт: способы входа, роли (без дублей) и
 * переопределения (у оставшегося приоритет) переходят аккаунту оставшейся карточки, сессии удаляемого
 * аккаунта закрываются, сам он удаляется; затем проверяется правило последнего администратора. Если
 * аккаунт был только у удаляемой карточки, {@code clients} уже перевёл его на оставшуюся — делать нечего.
 * Обычный {@code @EventListener}: выполняется синхронно в транзакции объединения, как требует
 * {@link ClientMerged}. Порядок блокировок строк: клиент (его держит {@code clients}) → аккаунт → роль
 * {@code DEVELOPER}.
 */
@Component
class ClientMergedListener {

    private final AccountMerger merger;
    private final AccessGuard guard;

    ClientMergedListener(AccountMerger merger, AccessGuard guard) {
        this.merger = merger;
        this.guard = guard;
    }

    @EventListener
    void on(ClientMerged event) {
        var removed = event.removedAccountId();
        var survivor = event.survivorAccountId();
        if (removed == null || survivor == null || removed.equals(survivor)) {
            return;
        }
        merger.merge(removed, survivor);
        guard.requireAdminNotLost();
    }
}
