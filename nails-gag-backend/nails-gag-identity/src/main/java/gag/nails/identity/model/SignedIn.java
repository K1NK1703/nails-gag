package gag.nails.identity.model;

import java.util.UUID;

/** Сессия открыта; {@code sessionToken} отдаётся клиенту один раз, в базе хранится только его хеш. */
public record SignedIn(String sessionToken, UUID accountId) implements LoginResult {

    /** Токен в логи не попадает. */
    @Override
    public String toString() {
        return "SignedIn[accountId=" + accountId + "]";
    }
}
