package gag.nails.clients.internal.consent;

import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentWithdrawalView;
import gag.nails.clients.model.WithdrawnBy;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** Запись журнала отзывов согласий; только добавление. */
@Entity
@Table(schema = "clients", name = "consent_withdrawal")
public class ConsentWithdrawalEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID clientId;

    @Enumerated(EnumType.STRING)
    private ConsentKind kind;

    private Instant withdrawnAt;

    @Enumerated(EnumType.STRING)
    private WithdrawnBy withdrawnBy;

    protected ConsentWithdrawalEntity() {}

    public ConsentWithdrawalEntity(UUID clientId, ConsentKind kind, Instant withdrawnAt, WithdrawnBy withdrawnBy) {
        this.clientId = clientId;
        this.kind = kind;
        this.withdrawnAt = withdrawnAt;
        this.withdrawnBy = withdrawnBy;
    }

    public ConsentWithdrawalView toView() {
        return new ConsentWithdrawalView(kind, withdrawnAt, withdrawnBy);
    }
}
