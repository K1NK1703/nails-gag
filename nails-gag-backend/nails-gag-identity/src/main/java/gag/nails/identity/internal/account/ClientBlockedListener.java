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
 * {@link ClientBlocked}. Порядок блокировок строк: клиент ({@code clients} блокирует его строку в начале
 * {@code block}) → аккаунт → роль {@code DEVELOPER} — тот же, что у объединения карточек (клиент → аккаунт).
 *
 * <p>Вход, записывающий подтверждённый номер, и удаление аккаунта берут строки в том же порядке (карточка, затем
 * аккаунт), поэтому со входом и удалением той же карточки блокировка не встаёт по кругу, а ждёт их фиксации.
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
