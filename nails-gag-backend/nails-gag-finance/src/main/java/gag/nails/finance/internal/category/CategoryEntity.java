package gag.nails.finance.internal.category;

import gag.nails.finance.model.CategoryView;
import gag.nails.finance.model.EntryKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** Категория доходов или расходов. Вид не меняется: на пару (id, вид) ссылаются записи и шаблоны. */
@Entity
@Table(schema = "finance", name = "category")
class CategoryEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private EntryKind kind;

    private String name;
    private boolean hidden;
    private int position;

    @Column(updatable = false)
    private Instant createdAt;

    protected CategoryEntity() {}

    CategoryEntity(EntryKind kind, String name, int position, Instant createdAt) {
        this.kind = kind;
        this.name = name;
        this.hidden = false;
        this.position = position;
        this.createdAt = createdAt;
    }

    void rename(String name) {
        this.name = name;
    }

    void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

    void setPosition(int position) {
        this.position = position;
    }

    UUID id() {
        return id;
    }

    EntryKind kind() {
        return kind;
    }

    String name() {
        return name;
    }

    boolean hidden() {
        return hidden;
    }

    CategoryView toView() {
        return new CategoryView(id, kind, name, hidden, position);
    }

    CategoryRef toRef() {
        return new CategoryRef(id, kind, name, hidden);
    }
}
