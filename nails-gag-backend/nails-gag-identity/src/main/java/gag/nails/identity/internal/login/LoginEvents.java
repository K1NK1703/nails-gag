package gag.nails.identity.internal.login;

import gag.nails.common.paging.Paged;
import gag.nails.common.paging.Paging;
import gag.nails.identity.model.ClientInfo;
import gag.nails.identity.model.LoginEventView;
import gag.nails.identity.model.LoginOutcome;
import gag.nails.identity.model.Provider;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Журнал входов: каждый исход {@code LoginFlow} — строка. Адрес и браузер обрезаются до ширины колонок:
 * журнал не должен срывать вход из-за длинного {@code User-Agent}.
 */
@Component
public class LoginEvents {

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
    public List<LoginEventView> latest(int limit) {
        return events.findAllByOrderByOccurredAtDescIdDesc(Limit.of(limit)).stream()
                .map(LoginEventEntity::toView)
                .toList();
    }

    /** Постранично, в порядке {@link #latest}. */
    public Paged<LoginEventView> page(Paging paging) {
        var page = events.findAllByOrderByOccurredAtDescIdDesc(PageRequest.of(paging.page(), paging.size()));
        return Paged.of(page.getContent(), paging, page.getTotalElements()).map(LoginEventEntity::toView);
    }

    private static @Nullable String truncate(@Nullable String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var trimmed = value.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
