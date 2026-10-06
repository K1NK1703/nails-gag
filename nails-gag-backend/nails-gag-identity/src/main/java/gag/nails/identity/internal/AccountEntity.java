package gag.nails.identity.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** Человек в системе; карточка клиента ссылается на него через {@code clients.client.account_id}. */
@Entity
@Table(schema = "identity", name = "account")
class AccountEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    /** Бизнес-время из {@code Clock}, не аудит. */
    private Instant createdAt;

    private Instant lastLoginAt;

    protected AccountEntity() {}

    AccountEntity(Instant now) {
        this.createdAt = now;
        this.lastLoginAt = now;
    }

    void loggedIn(Instant now) {
        this.lastLoginAt = now;
    }

    UUID id() {
        return id;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant lastLoginAt() {
        return lastLoginAt;
    }
}
