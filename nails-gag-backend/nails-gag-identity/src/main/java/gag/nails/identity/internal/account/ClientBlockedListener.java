package gag.nails.identity.internal.account;

import gag.nails.clients.event.ClientBlocked;
import gag.nails.identity.internal.access.AccessGuard;
import gag.nails.identity.internal.session.SessionRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Блокировка клиента: сессии его аккаунта закрываются сразу, затем проверяется правило последнего
 * администратора — если заблокирован единственный администратор, {@code LastAccessAdminException}
 * откатывает блокировку (пока администраторов нет вовсе, терять нечего — блокировка проходит). Обычный
 * {@code @EventListener}: выполняется синхронно в транзакции {@code clients.block}, как требует
 * {@link ClientBlocked}. Порядок блокировок строк: клиент (его держит {@code clients}) → аккаунт → роль
 * {@code DEVELOPER}.
 *
 * <p>Известное ограничение: вход при фиксации обновляет строки аккаунта ({@code last_login_at}) и, если
 * записывает подтверждённый номер, карточки — в обратном порядке, аккаунт → клиент. Совпав по времени с
 * блокировкой той же карточки (или с удалением того же аккаунта, где порядок тот же: карточка → аккаунт),
 * они могут взаимно заблокироваться; PostgreSQL прервёт одну из транзакций, её достаточно повторить.
 */
@Component
class ClientBlockedListener {

    private final AccountRepository accounts;
    private final SessionRepository sessions;
    private final AccessGuard guard;

    ClientBlockedListener(AccountRepository accounts, SessionRepository sessions, AccessGuard guard) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.guard = guard;
    }

    @EventListener
    void on(ClientBlocked event) {
        var accountId = event.accountId();
        if (accountId == null) {
            // Карточка без аккаунта: сессий нет, администратором она быть не может — блокировку роли не берём.
            return;
        }
        // Под блокировкой строки аккаунта: вход, фиксирующий сессию в это же время, либо уже виден этой
        // выборке, либо сам перепроверит карточку после фиксации (LoginWriter.rejectIfBlocked) и закроет её.
        accounts.lockById(accountId);
        sessions.deleteAllByAccountId(accountId);
        guard.requireAdminNotLost();
    }
}
