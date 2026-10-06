package gag.nails.identity.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** Сессия; в базе только SHA-256 токена, сам токен не хранится. */
@Entity
@Table(schema = "identity", name = "session")
class SessionEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(updatable = false)
    private byte[] tokenHash;

    private UUID accountId;
    private UUID loginMethodId;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant lastUsedAt;

    protected SessionEntity() {}

    SessionEntity(byte[] tokenHash, UUID accountId, UUID loginMethodId, Instant now) {
        this.tokenHash = tokenHash.clone();
        this.accountId = accountId;
        this.loginMethodId = loginMethodId;
        this.createdAt = now;
        this.lastUsedAt = now;
    }

    void used(Instant now) {
        this.lastUsedAt = now;
    }

    UUID id() {
        return id;
    }

    UUID accountId() {
        return accountId;
    }

    UUID loginMethodId() {
        return loginMethodId;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant lastUsedAt() {
        return lastUsedAt;
    }
}
