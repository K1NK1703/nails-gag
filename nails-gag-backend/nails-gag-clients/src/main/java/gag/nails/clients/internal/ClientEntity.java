package gag.nails.clients.internal;

import gag.nails.clients.ClientSource;
import gag.nails.clients.ClientView;
import gag.nails.clients.CreateClient;
import gag.nails.clients.RegisterClient;
import gag.nails.clients.UpdateClient;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.MonthDay;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

@Entity
@Table(schema = "clients", name = "client")
class ClientEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String firstName;
    private @Nullable String lastName;
    private @Nullable String patronymic;
    private @Nullable String phone;
    private @Nullable String email;
    private @Nullable Integer birthDay;
    private @Nullable Integer birthMonth;
    private @Nullable String masterNote;

    @Enumerated(EnumType.STRING)
    private ClientSource source;

    private @Nullable UUID accountId;
    private boolean prepaymentRequired;

    // Аудит, не бизнес-время: бизнес-время берётся только из Clock.
    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    protected ClientEntity() {}

    /** Карточка от мастера: телефон уже нормализован. */
    static ClientEntity card(CreateClient command, @Nullable String phone) {
        var client = new ClientEntity();
        client.source = ClientSource.ADDED_BY_MASTER;
        client.phone = phone;
        client.apply(new UpdateClient(
                command.firstName(), command.lastName(), command.patronymic(), command.email(), command.birthday()));
        return client;
    }

    /** Зарегистрировался через вход: телефон уже нормализован. */
    static ClientEntity registered(RegisterClient command, @Nullable String phone) {
        var client = new ClientEntity();
        client.source = ClientSource.SELF_REGISTERED;
        client.accountId = command.accountId();
        client.phone = phone;
        client.apply(new UpdateClient(command.firstName(), null, null, command.email(), null));
        return client;
    }

    void apply(UpdateClient command) {
        this.firstName = command.firstName();
        this.lastName = command.lastName();
        this.patronymic = command.patronymic();
        this.email = command.email();
        var birthday = command.birthday();
        this.birthDay = birthday == null ? null : birthday.getDayOfMonth();
        this.birthMonth = birthday == null ? null : birthday.getMonthValue();
    }

    UUID id() {
        return id;
    }

    Optional<String> phone() {
        return Optional.ofNullable(phone);
    }

    void setPhone(@Nullable String phone) {
        this.phone = phone;
    }

    Optional<String> masterNote() {
        return Optional.ofNullable(masterNote);
    }

    void setMasterNote(@Nullable String masterNote) {
        this.masterNote = masterNote;
    }

    Optional<UUID> accountId() {
        return Optional.ofNullable(accountId);
    }

    void attachAccount(UUID accountId) {
        this.accountId = accountId;
    }

    boolean prepaymentRequired() {
        return prepaymentRequired;
    }

    void setPrepaymentRequired(boolean prepaymentRequired) {
        this.prepaymentRequired = prepaymentRequired;
    }

    /** Переносит на этого клиента то, чего у него нет, с удаляемого дубля. */
    void absorb(ClientEntity other) {
        if (lastName == null) lastName = other.lastName;
        if (patronymic == null) patronymic = other.patronymic;
        if (email == null) email = other.email;
        if (birthDay == null) {
            birthDay = other.birthDay;
            birthMonth = other.birthMonth;
        }
        if (other.masterNote != null) {
            masterNote = masterNote == null ? other.masterNote : masterNote + "\n" + other.masterNote;
        }
    }

    ClientView toView() {
        var birthday = birthDay == null || birthMonth == null ? null : MonthDay.of(birthMonth, birthDay);
        return new ClientView(
                id, firstName, lastName, patronymic, phone, email, birthday, source, accountId, prepaymentRequired);
    }
}
