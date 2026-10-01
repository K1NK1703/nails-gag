package gag.nails.clients.internal;

import gag.nails.clients.ChangedBy;
import gag.nails.clients.PrepaymentFlagChangeView;
import gag.nails.clients.PrepaymentFlagReason;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

@Entity
@Table(schema = "clients", name = "prepayment_flag_change")
class PrepaymentFlagChangeEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID clientId;
    private boolean required;

    @Enumerated(EnumType.STRING)
    private PrepaymentFlagReason reason;

    @Enumerated(EnumType.STRING)
    private ChangedBy setBy;

    private @Nullable UUID bookingId;
    private @Nullable String comment;
    private Instant changedAt;

    protected PrepaymentFlagChangeEntity() {}

    PrepaymentFlagChangeEntity(
            UUID clientId,
            boolean required,
            PrepaymentFlagReason reason,
            ChangedBy setBy,
            @Nullable UUID bookingId,
            @Nullable String comment,
            Instant changedAt) {
        this.clientId = clientId;
        this.required = required;
        this.reason = reason;
        this.setBy = setBy;
        this.bookingId = bookingId;
        this.comment = comment;
        this.changedAt = changedAt;
    }

    PrepaymentFlagChangeView toView() {
        return new PrepaymentFlagChangeView(required, reason, setBy, bookingId, comment, changedAt);
    }
}
