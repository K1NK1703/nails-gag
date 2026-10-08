package gag.nails.identity.internal.session;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Открытие сессии для {@code LoginFlow}: токен отдаётся клиенту один раз, в базе остаётся только хеш. */
@Component
public class SessionOpener {

    private final SessionRepository sessions;
    private final Clock clock;

    SessionOpener(SessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    public String open(UUID accountId, UUID loginMethodId) {
        var token = OpaqueTokens.token();
        sessions.save(new SessionEntity(OpaqueTokens.hash(token), accountId, loginMethodId, clock.instant()));
        return token;
    }
}
