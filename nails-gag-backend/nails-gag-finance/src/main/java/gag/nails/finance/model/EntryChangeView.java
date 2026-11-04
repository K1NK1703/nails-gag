package gag.nails.finance.model;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Изменение ручной записи: было → стало, кто, когда.
 *
 * @param before запись до изменения; пусто у {@link EntryAction#CREATE}
 * @param after запись после изменения; пусто у {@link EntryAction#DELETE}
 * @param accountId аккаунт, который изменил запись; пусто — система (повторяющийся шаблон) или актор без аккаунта
 */
public record EntryChangeView(
        UUID id,
        UUID entryId,
        EntryAction action,
        @Nullable EntrySnapshot before,
        @Nullable EntrySnapshot after,
        @Nullable UUID accountId,
        Instant changedAt) {}
