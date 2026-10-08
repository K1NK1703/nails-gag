package gag.nails.identity.internal.account;

import gag.nails.identity.model.LoginMethodView;
import gag.nails.identity.model.Provider;
import jakarta.persistence.Column;
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
 * Способ входа у провайдера. {@code phone} (E.164) и {@code email} (нижний регистр) — только
 * подтверждённые провайдером: по ним сопоставляются аккаунты при входе.
 */
@Entity
@Table(schema = "identity", name = "login_method")
public class LoginMethodEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID accountId;

    @Enumerated(EnumType.STRING)
    private Provider provider;

    private String subject;
    private @Nullable String phone;
    private @Nullable String email;
    private @Nullable String displayName;
    private @Nullable String avatarUrl;

    @Column(updatable = false)
    private Instant linkedAt;

    private Instant lastUsedAt;

    protected LoginMethodEntity() {}

    public LoginMethodEntity(
            UUID accountId,
            Provider provider,
            String subject,
            @Nullable String phone,
            @Nullable String email,
            @Nullable String displayName,
            @Nullable String avatarUrl,
            Instant now) {
        this.accountId = accountId;
        this.provider = provider;
        this.subject = subject;
        this.phone = phone;
        this.email = email;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.linkedAt = now;
        this.lastUsedAt = now;
    }

    /** Свежие данные профиля при очередном входе этим способом. */
    public void used(
            @Nullable String phone,
            @Nullable String email,
            @Nullable String displayName,
            @Nullable String avatarUrl,
            Instant now) {
        this.phone = phone;
        this.email = email;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.lastUsedAt = now;
    }

    /** Объединение аккаунтов: способ входа переходит оставшемуся. */
    void reassignTo(UUID accountId) {
        this.accountId = accountId;
    }

    public UUID id() {
        return id;
    }

    public UUID accountId() {
        return accountId;
    }

    Provider provider() {
        return provider;
    }

    String subject() {
        return subject;
    }

    @Nullable
    String phone() {
        return phone;
    }

    @Nullable
    String email() {
        return email;
    }

    @Nullable
    public String displayName() {
        return displayName;
    }

    @Nullable
    public String avatarUrl() {
        return avatarUrl;
    }

    Instant linkedAt() {
        return linkedAt;
    }

    Instant lastUsedAt() {
        return lastUsedAt;
    }

    public LoginMethodView toView() {
        return new LoginMethodView(provider, displayName, avatarUrl, linkedAt, lastUsedAt);
    }
}
