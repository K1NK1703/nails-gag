package gag.nails.clients.internal;

import gag.nails.clients.ChangedBy;
import gag.nails.clients.ClientMerged;
import gag.nails.clients.MergeResult;
import gag.nails.clients.PrepaymentFlagReason;
import gag.nails.clients.error.MergeTwoAccountsException;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Объединение двух клиентов в одного. Порядок шагов важен: телефон уникален, а журналы ссылаются на клиента. */
@Component
class ClientMerger {

    private final ClientRepository clients;
    private final ConsentRepository consents;
    private final PrepaymentFlagChangeRepository flagChanges;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ClientMerger(
            ClientRepository clients,
            ConsentRepository consents,
            PrepaymentFlagChangeRepository flagChanges,
            ApplicationEventPublisher events,
            Clock clock) {
        this.clients = clients;
        this.consents = consents;
        this.flagChanges = flagChanges;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Остаётся клиент с аккаунтом; если аккаунта нет ни у кого — {@code second} (цель объединения).
     *
     * @throws MergeTwoAccountsException если аккаунт есть у обоих
     */
    MergeResult merge(ClientEntity first, ClientEntity second) {
        if (first.accountId().isPresent() && second.accountId().isPresent()) {
            throw new MergeTwoAccountsException();
        }
        var survivor = first.accountId().isPresent() ? first : second;
        var removed = survivor == first ? second : first;

        var removedPhone = removed.phone().orElse(null);
        var flagToCarry = removed.prepaymentRequired() && !survivor.prepaymentRequired();

        consents.reassign(removed.id(), survivor.id());
        flagChanges.reassign(removed.id(), survivor.id());
        survivor.absorb(removed);
        // удаляем до переноса телефона: иначе уникальный индекс увидит два одинаковых номера
        clients.delete(removed);
        clients.flush();
        if (survivor.phone().isEmpty() && removedPhone != null) {
            survivor.setPhone(removedPhone);
        }
        if (flagToCarry) {
            survivor.setPrepaymentRequired(true);
            flagChanges.save(new PrepaymentFlagChangeEntity(
                    survivor.id(), true, PrepaymentFlagReason.MERGED, ChangedBy.MASTER, null, null, clock.instant()));
        }
        events.publishEvent(new ClientMerged(removed.id(), survivor.id(), clock.instant()));
        return new MergeResult(survivor.id(), removed.id());
    }
}
