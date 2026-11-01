package gag.nails.finance.internal.entry;

import gag.nails.finance.model.EntryAction;
import gag.nails.finance.model.EntryChangeView;
import gag.nails.finance.model.EntrySnapshot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Журнал изменений ручных записей. Пишется только в транзакции самого изменения ({@code MANDATORY}): изменение и его
 * строка журнала фиксируются или откатываются вместе.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class EntryJournal {

    private final EntryChangeRepository changes;
    private final EntrySnapshots snapshots;

    EntryJournal(EntryChangeRepository changes, EntrySnapshots snapshots) {
        this.changes = changes;
        this.snapshots = snapshots;
    }

    void created(UUID entryId, EntrySnapshot after, @Nullable UUID accountId, Instant at) {
        record(entryId, EntryAction.CREATE, null, after, accountId, at);
    }

    void updated(UUID entryId, EntrySnapshot before, EntrySnapshot after, @Nullable UUID accountId, Instant at) {
        record(entryId, EntryAction.UPDATE, before, after, accountId, at);
    }

    void deleted(UUID entryId, EntrySnapshot before, @Nullable UUID accountId, Instant at) {
        record(entryId, EntryAction.DELETE, before, null, accountId, at);
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    List<EntryChangeView> history(UUID entryId) {
        return changes.findAllByEntryIdInOrder(entryId).stream()
                .map(change -> new EntryChangeView(
                        change.id(),
                        change.entryId(),
                        change.action(),
                        snapshots.read(change.before()),
                        snapshots.read(change.after()),
                        change.accountId(),
                        change.changedAt()))
                .toList();
    }

    private void record(
            UUID entryId,
            EntryAction action,
            @Nullable EntrySnapshot before,
            @Nullable EntrySnapshot after,
            @Nullable UUID accountId,
            Instant at) {
        changes.save(
                new EntryChangeEntity(entryId, action, snapshots.write(before), snapshots.write(after), accountId, at));
    }
}
