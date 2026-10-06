package gag.nails.identity.internal;

import gag.nails.identity.LoginMethodView;
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
 * Способ входа у провайдера. {@code phone} (E.164) и {@code email} (нижний регистр) — только
 * подтверждённые провайдером: по ним сопоставляются аккаунты при входе.
 */
@Entity
@Table(schema = "identity", name = "login_method")
class LoginMethodEntity {

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
    private Instant linkedAt;
    private Instant lastUsedAt;

    protected LoginMethodEntity() {}

    LoginMethodEntity(
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
    void used(
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

    UUID id() {
        return id;
    }

    UUID accountId() {
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
    String displayName() {
        return displayName;
    }

    @Nullable
    String avatarUrl() {
        return avatarUrl;
    }

    Instant linkedAt() {
        return linkedAt;
    }

    Instant lastUsedAt() {
        return lastUsedAt;
    }

    LoginMethodView toView() {
        return new LoginMethodView(provider, displayName, avatarUrl, linkedAt, lastUsedAt);
    }
}
