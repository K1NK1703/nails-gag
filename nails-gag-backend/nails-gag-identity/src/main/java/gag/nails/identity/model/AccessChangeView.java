package gag.nails.identity.model;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Section;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Строка журнала прав.
 *
 * @param actorAccountId кто изменил; пусто у {@link AccessChangeKind#BOOTSTRAP}
 * @param targetAccountId чей доступ изменён; пусто у операций над самой ролью
 * @param roleId роль, если изменение касается роли
 * @param section раздел, если изменился уровень раздела
 */
public record AccessChangeView(
        UUID id,
        @Nullable UUID actorAccountId,
        @Nullable UUID targetAccountId,
        @Nullable UUID roleId,
        AccessChangeKind kind,
        @Nullable Section section,
        @Nullable AccessLevel oldLevel,
        @Nullable AccessLevel newLevel,
        Instant changedAt) {}
