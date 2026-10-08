package gag.nails.booking.internal.booking;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Строка на клиента: её блокировка выстраивает параллельные записи одного клиента в очередь. */
@Entity
@Table(schema = "booking", name = "client_lock")
class ClientLockEntity {

    /** Идентификатор клиента. */
    @Id
    private UUID id;

    protected ClientLockEntity() {}
}
