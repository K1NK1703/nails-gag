package gag.nails.identity.internal;

import gag.nails.identity.ClientInfo;
import gag.nails.identity.LoginEventView;
import gag.nails.identity.LoginOutcome;
import gag.nails.identity.Provider;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * Журнал входов: каждый исход {@code LoginFlow} — строка. Адрес и браузер обрезаются до ширины колонок:
 * журнал не должен срывать вход из-за длинного {@code User-Agent}.
 */
@Component
class LoginEvents {

    /** Ширина колонок {@code ip} и {@code user_agent} из миграции {@code 0001-init}. */
    static final int MAX_IP_LENGTH = 45;

    static final int MAX_USER_AGENT_LENGTH = 512;

    private final LoginEventRepository events;
    private final Clock clock;

    LoginEvents(LoginEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    void record(@Nullable UUID accountId, Provider provider, LoginOutcome outcome, ClientInfo client) {
        events.save(new LoginEventEntity(
                accountId,
                provider,
                outcome,
                truncate(client.ip(), MAX_IP_LENGTH),
                truncate(client.userAgent(), MAX_USER_AGENT_LENGTH),
                clock.instant()));
    }

    /** Новые первыми. */
    List<LoginEventView> latest(int limit) {
        return events.findAllByOrderByOccurredAtDescIdDesc(Limit.of(limit)).stream()
                .map(LoginEventEntity::toView)
                .toList();
    }

    private static @Nullable String truncate(@Nullable String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var trimmed = value.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
