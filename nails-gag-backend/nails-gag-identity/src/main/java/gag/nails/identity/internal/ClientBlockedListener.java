package gag.nails.identity.internal;

import gag.nails.clients.event.ClientBlocked;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Блокировка клиента: сессии его аккаунта закрываются сразу, затем проверяется правило последнего
 * администратора — если заблокирован единственный администратор, {@code LastAccessAdminException}
 * откатывает блокировку (пока администраторов нет вовсе, терять нечего — блокировка проходит). Обычный {@code @EventListener}: выполняется синхронно в транзакции
 * {@code clients.block}, как требует {@link ClientBlocked}. Порядок блокировок строк: клиент (его держит
 * {@code clients}) → роль {@code DEVELOPER}.
 */
@Component
class ClientBlockedListener {

    private final SessionRepository sessions;
    private final AccessGuard guard;

    ClientBlockedListener(SessionRepository sessions, AccessGuard guard) {
        this.sessions = sessions;
        this.guard = guard;
    }

    @EventListener
    void on(ClientBlocked event) {
        var accountId = event.accountId();
        if (accountId != null) {
            sessions.deleteAllByAccountId(accountId);
        }
        guard.requireAdminNotLost();
    }
}
