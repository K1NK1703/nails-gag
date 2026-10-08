package gag.nails.catalog.internal;

import gag.nails.catalog.command.CreateService;
import gag.nails.catalog.command.UpdateService;
import gag.nails.catalog.model.ServiceKind;
import gag.nails.catalog.model.ServiceView;
import gag.nails.common.error.ValidationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

@Entity
@Table(schema = "catalog", name = "service")
class ServiceEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Enumerated(EnumType.STRING)
    private ServiceKind kind;

    private String title;
    private String description;

    @Column(precision = 12, scale = 2)
    private BigDecimal price;

    private @Nullable Integer durationMinutes;
    private String category;
    private int sortOrder;
    private boolean showOnLanding;
    private boolean highlightOnLanding;
    private boolean active;

    // Аудит, не бизнес-время: бизнес-время берётся только из Clock.
    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    protected ServiceEntity() {}

    ServiceEntity(CreateService command, int sortOrder) {
        this.kind = command.kind();
        this.sortOrder = sortOrder;
        this.active = true;
        apply(new UpdateService(
                command.title(),
                command.description(),
                command.price(),
                command.durationMinutes(),
                command.category(),
                command.showOnLanding(),
                command.highlightOnLanding()));
    }

    /**
     * Применяет правку, проверив инвариант «длительность есть у основных услуг и только у них».
     * {@code final}: вызывается из конструктора, иначе javac предупреждает о this-escape.
     */
    final void apply(UpdateService command) {
        requireDurationMatchesKind(kind, command.durationMinutes());
        this.title = command.title();
        this.description = command.description();
        this.price = command.price();
        this.durationMinutes = command.durationMinutes();
        this.category = command.category();
        this.showOnLanding = command.showOnLanding();
        this.highlightOnLanding = command.highlightOnLanding();
    }

    void setActive(boolean active) {
        this.active = active;
    }

    void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    UUID id() {
        return id;
    }

    ServiceKind kind() {
        return kind;
    }

    String title() {
        return title;
    }

    int sortOrder() {
        return sortOrder;
    }

    boolean active() {
        return active;
    }

    ServiceView toView() {
        return new ServiceView(
                id,
                kind,
                title,
                description,
                price,
                durationMinutes,
                category,
                sortOrder,
                showOnLanding,
                highlightOnLanding,
                active);
    }

    /** Дополнения слот не занимают, поэтому длительности у них нет. */
    private static void requireDurationMatchesKind(ServiceKind kind, @Nullable Integer durationMinutes) {
        if (kind == ServiceKind.MAIN && durationMinutes == null) {
            throw new ValidationException("durationMinutes", "required for a main service");
        }
        if (kind == ServiceKind.ADDON && durationMinutes != null) {
            throw new ValidationException("durationMinutes", "not allowed for an add-on");
        }
    }
}
