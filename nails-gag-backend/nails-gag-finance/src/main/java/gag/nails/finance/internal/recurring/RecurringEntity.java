package gag.nails.finance.internal.recurring;

import gag.nails.finance.model.EntryKind;
import gag.nails.finance.model.RecurringView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

/**
 * Шаблон повторяющейся ежемесячной записи. Вид не меняется: категория шаблона — всегда того же вида.
 *
 * <p>{@code generatedThrough} — прогресс ночной задачи: по какой день включительно записи шаблона уже созданы (или
 * пропущены на паузе); пусто — ещё ни одной. Записи создаются только за дни после него, поэтому перенесённая или
 * удалённая запись шаблона за свой исходный день заново не создаётся.
 */
@Entity
@Table(schema = "finance", name = "recurring")
class RecurringEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private EntryKind kind;

    private UUID categoryId;
    private BigDecimal amount;
    private @Nullable String comment;
    private int dayOfMonth;
    private LocalDate startsOn;
    private @Nullable LocalDate endsOn;
    private boolean paused;
    private @Nullable LocalDate generatedThrough;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    protected RecurringEntity() {}

    RecurringEntity(EntryKind kind, RecurringFields fields, Instant createdAt) {
        this.kind = kind;
        this.paused = false;
        this.createdAt = createdAt;
        apply(fields, createdAt);
    }

    /** {@code final}: вызывается из конструктора, иначе javac предупреждает о this-escape. */
    final void apply(RecurringFields fields, Instant updatedAt) {
        this.categoryId = fields.categoryId();
        this.amount = fields.amount();
        this.comment = fields.comment();
        this.dayOfMonth = fields.dayOfMonth();
        this.startsOn = fields.startsOn();
        this.endsOn = fields.endsOn();
        this.updatedAt = updatedAt;
    }

    /** День записи шаблона в месяце: его число, а в коротком месяце — последний день. */
    LocalDate dueIn(YearMonth month) {
        return dueIn(month, dayOfMonth);
    }

    static LocalDate dueIn(YearMonth month, int dayOfMonth) {
        return month.atDay(Math.min(dayOfMonth, month.lengthOfMonth()));
    }

    /** Прогресс не идёт назад: задача и пауза только продвигают его. */
    void advanceGeneratedThrough(LocalDate day) {
        if (generatedThrough == null || day.isAfter(generatedThrough)) {
            generatedThrough = day;
        }
    }

    /** Смена числа месяца ставит прогресс точно, в том числе назад ({@link RecurringSchedule#afterDayChange}). */
    void setGeneratedThrough(LocalDate day) {
        this.generatedThrough = day;
    }

    void setPaused(boolean paused, Instant updatedAt) {
        this.paused = paused;
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

    BigDecimal amount() {
        return amount;
    }

    @Nullable
    String comment() {
        return comment;
    }

    int dayOfMonth() {
        return dayOfMonth;
    }

    LocalDate startsOn() {
        return startsOn;
    }

    @Nullable
    LocalDate endsOn() {
        return endsOn;
    }

    boolean paused() {
        return paused;
    }

    @Nullable
    LocalDate generatedThrough() {
        return generatedThrough;
    }

    RecurringView toView(String categoryName) {
        return new RecurringView(
                id, kind, categoryId, categoryName, amount, comment, dayOfMonth, startsOn, endsOn, paused);
    }
}
