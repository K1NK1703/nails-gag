package gag.nails.identity.internal;

import gag.nails.identity.LoginEventView;
import gag.nails.identity.LoginOutcome;
import gag.nails.identity.Provider;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

/**
 * Строка журнала входов; хранится 90 дней. {@code accountId} пуст у неудачного входа без аккаунта и
 * обнуляется базой при удалении аккаунта.
 */
@Entity
@Table(schema = "identity", name = "login_event")
class LoginEventEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private @Nullable UUID accountId;

    @Enumerated(EnumType.STRING)
    private Provider provider;

    @Enumerated(EnumType.STRING)
    private LoginOutcome outcome;

    private @Nullable String ip;
    private @Nullable String userAgent;
    private Instant occurredAt;

    protected LoginEventEntity() {}

    LoginEventEntity(
            @Nullable UUID accountId,
            Provider provider,
            LoginOutcome outcome,
            @Nullable String ip,
            @Nullable String userAgent,
            Instant occurredAt) {
        this.accountId = accountId;
        this.provider = provider;
        this.outcome = outcome;
        this.ip = ip;
        this.userAgent = userAgent;
        this.occurredAt = occurredAt;
    }

    LoginEventView toView() {
        return new LoginEventView(id, accountId, provider, outcome, ip, userAgent, occurredAt);
    }
}
