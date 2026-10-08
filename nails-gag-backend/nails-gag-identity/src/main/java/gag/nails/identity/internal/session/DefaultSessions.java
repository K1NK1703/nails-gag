package gag.nails.identity.internal.session;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.identity.Sessions;
import gag.nails.identity.internal.account.AccountRepository;
import gag.nails.identity.internal.account.LoginMethodRepository;
import gag.nails.identity.model.Authenticated;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Сессии в PostgreSQL (спецификация 4.5). {@code authenticate} идёт в обычной транзакции: он может
 * удалить просроченную сессию и раз в пять минут обновить {@code last_used_at}.
 */
@Service
@Transactional
public class DefaultSessions implements Sessions {

    /** Срок от входа. */
    public static final Duration ABSOLUTE_LIFETIME = Duration.ofDays(90);

    /** Срок без активности у аккаунта с доступом к админке. */
    static final Duration STAFF_IDLE_LIFETIME = Duration.ofDays(14);

    /** Срок без активности у остальных. */
    public static final Duration CLIENT_IDLE_LIFETIME = Duration.ofDays(30);

    /** {@code last_used_at} обновляется не чаще: чтение не должно превращаться в запись на каждый запрос. */
    static final Duration LAST_USED_GRANULARITY = Duration.ofMinutes(5);

    private final SessionRepository sessions;
    private final AccountRepository accounts;
    private final LoginMethodRepository loginMethods;
    private final ActorAssembly assembly;
    private final Clock clock;

    DefaultSessions(
            SessionRepository sessions,
            AccountRepository accounts,
            LoginMethodRepository loginMethods,
            ActorAssembly assembly,
            Clock clock) {
        this.sessions = sessions;
        this.accounts = accounts;
        this.loginMethods = loginMethods;
        this.assembly = assembly;
        this.clock = clock;
    }

    @Override
    public Optional<Authenticated> authenticate(String token) {
        var found = sessions.findByTokenHash(OpaqueTokens.hash(token));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        var session = found.get();
        var account = accounts.findById(session.accountId()).orElse(null);
        var method = loginMethods.findById(session.loginMethodId()).orElse(null);
        if (account == null || method == null) {
            // Аккаунт или способ входа исчезли, а каскад ещё не дошёл: сессии больше не на что опираться.
            sessions.delete(session);
            return Optional.empty();
        }
        var card = assembly.card(account);
        var authenticated = assembly.assemble(account, method, card);
        var now = clock.instant();
        var idle = authenticated.actor().isStaff() ? STAFF_IDLE_LIFETIME : CLIENT_IDLE_LIFETIME;
        if (session.createdAt().plus(ABSOLUTE_LIFETIME).isBefore(now)
                || session.lastUsedAt().plus(idle).isBefore(now)) {
            sessions.delete(session);
            return Optional.empty();
        }
        if (card.map(c -> c.blocked() || c.erased()).orElse(false)) {
            sessions.deleteAllByAccountId(account.id());
            return Optional.empty();
        }
        if (!session.lastUsedAt().plus(LAST_USED_GRANULARITY).isAfter(now)) {
            session.used(now);
        }
        return Optional.of(authenticated);
    }

    @Override
    public void close(String token) {
        sessions.findByTokenHash(OpaqueTokens.hash(token)).ifPresent(sessions::delete);
    }

    @Override
    public void closeAll(Actor actor) {
        var accountId = actor.accountId();
        if (accountId == null) {
            throw new AccessDeniedException();
        }
        sessions.deleteAllByAccountId(accountId);
    }
}
