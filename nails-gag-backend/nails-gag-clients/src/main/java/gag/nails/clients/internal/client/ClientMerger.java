package gag.nails.clients.internal.client;

import gag.nails.clients.error.ClientErasedException;
import gag.nails.clients.event.ClientBlocked;
import gag.nails.clients.event.ClientMerged;
import gag.nails.clients.internal.consent.ConsentRepository;
import gag.nails.clients.internal.consent.ConsentWithdrawalRepository;
import gag.nails.clients.internal.prepayment.PrepaymentFlagChangeRepository;
import gag.nails.clients.internal.prepayment.PrepaymentFlagJournal;
import gag.nails.clients.model.ChangedBy;
import gag.nails.clients.model.MergeResult;
import gag.nails.clients.model.PrepaymentFlagReason;
import java.time.Clock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Объединение двух клиентов в одного. Порядок шагов важен: телефон и аккаунт уникальны, а журналы
 * ссылаются на клиента. Обе карточки вызывающий передаёт уже заблокированными
 * ({@link ClientRepository#findLockedById}).
 */
@Component
class ClientMerger {

    private final ClientRepository clients;
    private final ConsentRepository consents;
    private final ConsentWithdrawalRepository withdrawals;
    private final PrepaymentFlagChangeRepository flagChanges;
    private final PrepaymentFlagJournal flagJournal;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ClientMerger(
            ClientRepository clients,
            ConsentRepository consents,
            ConsentWithdrawalRepository withdrawals,
            PrepaymentFlagChangeRepository flagChanges,
            PrepaymentFlagJournal flagJournal,
            ApplicationEventPublisher events,
            Clock clock) {
        this.clients = clients;
        this.consents = consents;
        this.withdrawals = withdrawals;
        this.flagChanges = flagChanges;
        this.flagJournal = flagJournal;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Остаётся клиент с аккаунтом; если аккаунт у обоих или ни у кого — {@code target}.
     *
     * @throws ClientErasedException если одна из карточек обезличена
     */
    MergeResult merge(ClientEntity source, ClientEntity target) {
        var onlySourceRegistered =
                source.accountId().isPresent() && target.accountId().isEmpty();
        return onlySourceRegistered ? mergeInto(source, target) : mergeInto(target, source);
    }

    /**
     * Остаётся {@code survivor}, кем бы ни были аккаунты. Если аккаунт только у удаляемого, он переходит
     * оставшемуся; если у обоих — модуль входа сливает их по {@link ClientMerged}.
     *
     * @throws ClientErasedException если одна из карточек обезличена
     */
    MergeResult mergeInto(ClientEntity survivor, ClientEntity removed) {
        requireNotErased(survivor);
        requireNotErased(removed);
        var removedPhone = removed.phone().orElse(null);
        var removedAccountId = removed.accountId().orElse(null);
        var survivorAccountId = survivor.accountId().orElse(null);
        var flagToCarry = removed.prepaymentRequired() && !survivor.prepaymentRequired();
        var blockToCarry = removed.isBlocked() && !survivor.isBlocked();

        consents.reassign(removed.id(), survivor.id());
        withdrawals.reassign(removed.id(), survivor.id());
        flagChanges.reassign(removed.id(), survivor.id());
        survivor.absorb(removed);
        // удаляем до переноса телефона и аккаунта: иначе уникальные индексы увидят два одинаковых значения
        clients.delete(removed);
        clients.flush();
        if (survivor.phone().isEmpty() && removedPhone != null) {
            survivor.setPhone(removedPhone);
        }
        if (survivorAccountId == null && removedAccountId != null) {
            survivor.attachAccount(removedAccountId);
        }
        if (flagToCarry) {
            flagJournal.record(survivor, true, PrepaymentFlagReason.MERGED, ChangedBy.MASTER, null, null);
        }
        var now = clock.instant();
        events.publishEvent(new ClientMerged(removed.id(), survivor.id(), removedAccountId, survivorAccountId, now));
        // Оставшийся стал заблокированным из-за объединения: модулю входа пора закрыть его сессии.
        if (blockToCarry) {
            events.publishEvent(
                    new ClientBlocked(survivor.id(), survivor.accountId().orElse(null), now));
        }
        return new MergeResult(survivor.id(), removed.id());
    }

    private static void requireNotErased(ClientEntity client) {
        if (client.isErased()) {
            throw new ClientErasedException(client.id());
        }
    }
}
