package gag.nails.identity.internal.access;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Section;
import gag.nails.common.paging.Paged;
import gag.nails.common.paging.Paging;
import gag.nails.identity.model.AccessChangeKind;
import gag.nails.identity.model.AccessChangeView;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** Журнал прав: только добавление, по строке на каждое результативное изменение. */
@Component
public class AccessJournal {

    private final AccessChangeRepository changes;
    private final Clock clock;

    AccessJournal(AccessChangeRepository changes, Clock clock) {
        this.changes = changes;
        this.clock = clock;
    }

    /**
     * @param actorAccountId кто изменил; пусто у {@link AccessChangeKind#BOOTSTRAP}
     * @param targetAccountId чей доступ изменён; пусто у операций над самой ролью
     * @param section раздел и его старый и новый уровни, если изменился уровень раздела
     */
    public void record(
            @Nullable UUID actorAccountId,
            @Nullable UUID targetAccountId,
            @Nullable UUID roleId,
            AccessChangeKind kind,
            @Nullable Section section,
            @Nullable AccessLevel oldLevel,
            @Nullable AccessLevel newLevel) {
        changes.save(new AccessChangeEntity(
                actorAccountId, targetAccountId, roleId, kind, section, oldLevel, newLevel, clock.instant()));
    }

    /** Новые первыми. */
    List<AccessChangeView> latest(int limit) {
        return changes.findAllByOrderByChangedAtDescIdDesc(Limit.of(limit)).stream()
                .map(AccessChangeEntity::toView)
                .toList();
    }

    /** Постранично, в порядке {@link #latest}. */
    Paged<AccessChangeView> page(Paging paging) {
        var page = changes.findAllByOrderByChangedAtDescIdDesc(PageRequest.of(paging.page(), paging.size()));
        return Paged.of(page.getContent(), paging, page.getTotalElements()).map(AccessChangeEntity::toView);
    }
}
