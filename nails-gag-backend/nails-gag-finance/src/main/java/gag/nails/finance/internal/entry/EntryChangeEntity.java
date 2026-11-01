package gag.nails.finance.internal.entry;

import gag.nails.finance.model.EntryAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

/**
 * Строка журнала изменений ручной записи; не меняется. Снимки — JSON-текст ({@link EntrySnapshots}), в базе —
 * {@code jsonb}: строка приводится к нему при вставке, а читается как текст.
 */
@Entity
@Immutable
@Table(schema = "finance", name = "entry_change")
class EntryChangeEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID entryId;

    @Enumerated(EnumType.STRING)
    private EntryAction action;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "cast(? as jsonb)")
    private @Nullable String before;

    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(write = "cast(? as jsonb)")
    private @Nullable String after;

    private @Nullable UUID accountId;
    private Instant changedAt;

    protected EntryChangeEntity() {}

    EntryChangeEntity(
            UUID entryId,
            EntryAction action,
            @Nullable String before,
            @Nullable String after,
            @Nullable UUID accountId,
            Instant changedAt) {
        this.entryId = entryId;
        this.action = action;
        this.before = before;
        this.after = after;
        this.accountId = accountId;
        this.changedAt = changedAt;
    }

    UUID id() {
        return id;
    }

    UUID entryId() {
        return entryId;
    }

    EntryAction action() {
        return action;
    }

    @Nullable
    String before() {
        return before;
    }

    @Nullable
    String after() {
        return after;
    }

    @Nullable
    UUID accountId() {
        return accountId;
    }

    Instant changedAt() {
        return changedAt;
    }
}
