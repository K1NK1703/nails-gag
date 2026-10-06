package gag.nails.identity.internal;

import gag.nails.identity.Provider;
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
 * Незавершённая регистрация или привязка: профиль провайдера до согласия на обработку данных лежит
 * только здесь. Живёт 30 минут, билет одноразовый.
 *
 * <p>{@code REGISTER}: {@code clientId} — карточка без аккаунта, к которой привяжемся (может быть
 * пусто). {@code LINK}: {@code accountId} — аккаунт вошедшего, {@code otherAccountId} — аккаунт,
 * которому принадлежит способ входа (его объединят с первым).
 */
@Entity
@Table(schema = "identity", name = "pending_registration")
class PendingRegistrationEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private byte[] ticketHash;

    @Enumerated(EnumType.STRING)
    private PendingPurpose purpose;

    @Enumerated(EnumType.STRING)
    private Provider provider;

    private String subject;
    private @Nullable String phone;
    private @Nullable String email;
    private @Nullable String displayName;
    private @Nullable String avatarUrl;
    private @Nullable UUID clientId;
    private @Nullable UUID accountId;
    private @Nullable UUID otherAccountId;

    @Column(updatable = false)
    private Instant createdAt;

    protected PendingRegistrationEntity() {}

    private PendingRegistrationEntity(
            byte[] ticketHash,
            PendingPurpose purpose,
            Provider provider,
            String subject,
            @Nullable String phone,
            @Nullable String email,
            @Nullable String displayName,
            @Nullable String avatarUrl,
            @Nullable UUID clientId,
            @Nullable UUID accountId,
            @Nullable UUID otherAccountId,
            Instant now) {
        this.ticketHash = ticketHash.clone();
        this.purpose = purpose;
        this.provider = provider;
        this.subject = subject;
        this.phone = phone;
        this.email = email;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.clientId = clientId;
        this.accountId = accountId;
        this.otherAccountId = otherAccountId;
        this.createdAt = now;
    }

    static PendingRegistrationEntity register(
            byte[] ticketHash,
            Provider provider,
            String subject,
            @Nullable String phone,
            @Nullable String email,
            @Nullable String displayName,
            @Nullable String avatarUrl,
            @Nullable UUID clientId,
            Instant now) {
        return new PendingRegistrationEntity(
                ticketHash,
                PendingPurpose.REGISTER,
                provider,
                subject,
                phone,
                email,
                displayName,
                avatarUrl,
                clientId,
                null,
                null,
                now);
    }

    static PendingRegistrationEntity link(
            byte[] ticketHash,
            Provider provider,
            String subject,
            @Nullable String phone,
            @Nullable String email,
            @Nullable String displayName,
            @Nullable String avatarUrl,
            UUID accountId,
            UUID otherAccountId,
            Instant now) {
        return new PendingRegistrationEntity(
                ticketHash,
                PendingPurpose.LINK,
                provider,
                subject,
                phone,
                email,
                displayName,
                avatarUrl,
                null,
                accountId,
                otherAccountId,
                now);
    }

    UUID id() {
        return id;
    }

    PendingPurpose purpose() {
        return purpose;
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

    @Nullable
    UUID clientId() {
        return clientId;
    }

    @Nullable
    UUID accountId() {
        return accountId;
    }

    @Nullable
    UUID otherAccountId() {
        return otherAccountId;
    }

    Instant createdAt() {
        return createdAt;
    }
}
