package gag.nails.finance.internal.entry;

import gag.nails.finance.model.EntryKind;
import gag.nails.finance.model.EntrySnapshot;
import gag.nails.finance.model.EntryView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

/** Ручной доход или расход. Вид не меняется: категория записи — всегда того же вида. */
@Entity
@Table(schema = "finance", name = "entry")
class EntryEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private EntryKind kind;

    private UUID categoryId;
    private BigDecimal amount;
    private LocalDate occurredOn;
    private @Nullable String comment;

    @Column(updatable = false)
    private @Nullable UUID recurringId;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    protected EntryEntity() {}

    EntryEntity(EntryKind kind, EntryFields fields, Instant createdAt) {
        this.kind = kind;
        this.createdAt = createdAt;
        apply(fields, createdAt);
    }

    /** {@code final}: вызывается из конструктора, иначе javac предупреждает о this-escape. */
    final void apply(EntryFields fields, Instant updatedAt) {
        this.categoryId = fields.categoryId();
        this.amount = fields.amount();
        this.occurredOn = fields.occurredOn();
        this.comment = fields.comment();
        this.updatedAt = updatedAt;
    }

    UUID id() {
        return id;
    }

    EntryKind kind() {
        return kind;
    }

    UUID categoryId() {
        return categoryId;
    }

    /** Поля записи в журнале; название категории — текущее, его знает вызывающий. */
    EntrySnapshot snapshot(String categoryName) {
        return new EntrySnapshot(kind, categoryId, categoryName, amount, occurredOn, comment, recurringId);
    }

    /** Снимок записи, если применить к ней {@code fields}; сама запись не меняется. */
    EntrySnapshot snapshotWith(EntryFields fields, String categoryName) {
        return new EntrySnapshot(
                kind,
                fields.categoryId(),
                categoryName,
                fields.amount(),
                fields.occurredOn(),
                fields.comment(),
                recurringId);
    }

    EntryView toView(String categoryName) {
        return new EntryView(
                id, kind, categoryId, categoryName, amount, occurredOn, comment, recurringId, createdAt, updatedAt);
    }
}
