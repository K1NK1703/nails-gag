package gag.nails.clients.internal.client;

import gag.nails.clients.command.CreateClient;
import gag.nails.clients.command.RegisterClient;
import gag.nails.clients.command.UpdateClient;
import gag.nails.clients.model.ClientSource;
import gag.nails.clients.model.ClientView;
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
public class ClientEntity {

    /** Имя обезличенной карточки: личных данных у неё больше нет, а записи и история остаются. */
    static final String ERASED_FIRST_NAME = "Удалённый клиент";

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
    private @Nullable String contactPhone;
    private @Nullable Instant blockedAt;
    private @Nullable String blockReason;
    private @Nullable Instant erasedAt;

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

    public UUID id() {
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

    /** Пустая заметка стирается, края обрезаются. */
    void setMasterNote(String masterNote) {
        this.masterNote = masterNote.isBlank() ? null : masterNote.strip();
    }

    Optional<UUID> accountId() {
        return Optional.ofNullable(accountId);
    }

    void attachAccount(UUID accountId) {
        this.accountId = accountId;
    }

    Optional<String> contactPhone() {
        return Optional.ofNullable(contactPhone);
    }

    /** Номер для связи уже нормализован; {@code null} стирает. */
    void setContactPhone(@Nullable String contactPhone) {
        this.contactPhone = contactPhone;
    }

    boolean isBlocked() {
        return blockedAt != null;
    }

    Optional<String> blockReason() {
        return Optional.ofNullable(blockReason);
    }

    /** Время первой блокировки сохраняется, причина заменяется; пустая причина стирается. */
    void block(@Nullable String reason, Instant now) {
        if (blockedAt == null) {
            blockedAt = now;
        }
        blockReason = reason == null || reason.isBlank() ? null : reason.strip();
    }

    void unblock() {
        blockedAt = null;
        blockReason = null;
    }

    boolean isErased() {
        return erasedAt != null;
    }

    /**
     * Обезличивание: остаются имя-заглушка, источник, признак предоплаты и сама блокировка (без причины).
     * Набор стираемых полей — тот же, что в ограничении {@code client_erased_check}.
     */
    void erase(Instant now) {
        firstName = ERASED_FIRST_NAME;
        lastName = null;
        patronymic = null;
        phone = null;
        contactPhone = null;
        email = null;
        birthDay = null;
        birthMonth = null;
        masterNote = null;
        accountId = null;
        blockReason = null;
        erasedAt = now;
    }

    boolean prepaymentRequired() {
        return prepaymentRequired;
    }

    public void setPrepaymentRequired(boolean prepaymentRequired) {
        this.prepaymentRequired = prepaymentRequired;
    }

    /**
     * Переносит на этого клиента то, чего у него нет, с удаляемого дубля — кроме телефона, аккаунта и
     * признака предоплаты: ими занимается объединение. Блокировка переходит тоже: оставшийся
     * заблокирован, если был заблокирован любой из двух, с более ранним временем и своей причиной, а
     * если своей нет — с причиной удаляемого.
     */
    void absorb(ClientEntity other) {
        if (lastName == null) lastName = other.lastName;
        if (patronymic == null) patronymic = other.patronymic;
        if (email == null) email = other.email;
        if (contactPhone == null) contactPhone = other.contactPhone;
        if (birthDay == null) {
            birthDay = other.birthDay;
            birthMonth = other.birthMonth;
        }
        if (other.masterNote != null) {
            masterNote = masterNote == null ? other.masterNote : masterNote + "\n" + other.masterNote;
        }
        if (other.blockedAt != null) {
            blockedAt = blockedAt == null || other.blockedAt.isBefore(blockedAt) ? other.blockedAt : blockedAt;
            if (blockReason == null) blockReason = other.blockReason;
        }
    }

    ClientView toView() {
        var birthday = birthDay == null || birthMonth == null ? null : MonthDay.of(birthMonth, birthDay);
        return new ClientView(
                id,
                firstName,
                lastName,
                patronymic,
                phone,
                email,
                birthday,
                source,
                accountId,
                prepaymentRequired,
                contactPhone,
                isBlocked(),
                isErased());
    }
}
