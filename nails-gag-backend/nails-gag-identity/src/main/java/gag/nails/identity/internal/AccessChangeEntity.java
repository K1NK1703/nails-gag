package gag.nails.identity.internal;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Section;
import gag.nails.identity.AccessChangeKind;
import gag.nails.identity.AccessChangeView;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

/** Строка журнала прав; только добавление. {@code actorAccountId} пуст у {@code BOOTSTRAP}. */
@Entity
@Table(schema = "identity", name = "access_change")
class AccessChangeEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private @Nullable UUID actorAccountId;
    private @Nullable UUID targetAccountId;
    private @Nullable UUID roleId;

    @Enumerated(EnumType.STRING)
    private AccessChangeKind kind;

    @Enumerated(EnumType.STRING)
    private @Nullable Section section;

    @Enumerated(EnumType.STRING)
    private @Nullable AccessLevel oldLevel;

    @Enumerated(EnumType.STRING)
    private @Nullable AccessLevel newLevel;

    private Instant changedAt;

    protected AccessChangeEntity() {}

    AccessChangeEntity(
            @Nullable UUID actorAccountId,
            @Nullable UUID targetAccountId,
            @Nullable UUID roleId,
            AccessChangeKind kind,
            @Nullable Section section,
            @Nullable AccessLevel oldLevel,
            @Nullable AccessLevel newLevel,
            Instant changedAt) {
        this.actorAccountId = actorAccountId;
        this.targetAccountId = targetAccountId;
        this.roleId = roleId;
        this.kind = kind;
        this.section = section;
        this.oldLevel = oldLevel;
        this.newLevel = newLevel;
        this.changedAt = changedAt;
    }

    AccessChangeView toView() {
        return new AccessChangeView(
                id, actorAccountId, targetAccountId, roleId, kind, section, oldLevel, newLevel, changedAt);
    }
}
