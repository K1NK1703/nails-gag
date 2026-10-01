package gag.nails.clients.internal;

import gag.nails.clients.ChangedBy;
import gag.nails.clients.PrepaymentFlagReason;
import java.time.Clock;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Единственное место, где меняется признак предоплаты: значение на клиенте и строка журнала всегда
 * идут вместе. Каждое изменение попадает в журнал, даже если значение не поменялось.
 */
@Component
class PrepaymentFlagJournal {

    private final PrepaymentFlagChangeRepository flagChanges;
    private final Clock clock;

    PrepaymentFlagJournal(PrepaymentFlagChangeRepository flagChanges, Clock clock) {
        this.flagChanges = flagChanges;
        this.clock = clock;
    }

    void record(
            ClientEntity client,
            boolean required,
            PrepaymentFlagReason reason,
            ChangedBy setBy,
            @Nullable UUID bookingId,
            @Nullable String comment) {
        client.setPrepaymentRequired(required);
        flagChanges.save(new PrepaymentFlagChangeEntity(
                client.id(), required, reason, setBy, bookingId, comment, clock.instant()));
    }
}
