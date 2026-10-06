package gag.nails.identity.internal;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Section;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/** Личное переопределение уровня раздела у аккаунта; {@link AccessLevel#NONE} — явный запрет. */
@Entity
@Table(schema = "identity", name = "access_override")
class AccessOverrideEntity {

    @EmbeddedId
    private AccessOverrideId id;

    @Enumerated(EnumType.STRING)
    private AccessLevel level;

    protected AccessOverrideEntity() {}

    AccessOverrideEntity(UUID accountId, Section section, AccessLevel level) {
        this.id = new AccessOverrideId(accountId, section);
        this.level = level;
    }

    void setLevel(AccessLevel level) {
        this.level = level;
    }

    UUID accountId() {
        return id.accountId();
    }

    Section section() {
        return id.section();
    }

    AccessLevel level() {
        return level;
    }
}
