package gag.nails.clients.internal;

import gag.nails.clients.ChangedBy;
import gag.nails.clients.ClientService;
import gag.nails.clients.ClientView;
import gag.nails.clients.ConsentSource;
import gag.nails.clients.CreateClient;
import gag.nails.clients.MergeResult;
import gag.nails.clients.PrepaymentFlagChange;
import gag.nails.clients.PrepaymentFlagReason;
import gag.nails.clients.RecordConsent;
import gag.nails.clients.RegisterClient;
import gag.nails.clients.UpdateClient;
import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.clients.error.ConsentVersionOutdatedException;
import gag.nails.clients.error.PhoneTakenException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.common.validation.CommandValidator;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultClientService implements ClientService {

    /** Причины, которые ставит только мастер или объединение; правилу системы они недоступны. */
    private static final Set<PrepaymentFlagReason> MASTER_ONLY_REASONS = EnumSet.of(
            PrepaymentFlagReason.MASTER_SET, PrepaymentFlagReason.MASTER_WAIVED, PrepaymentFlagReason.MERGED);

    /** Та же граница, что у {@link PrepaymentFlagChange#comment()}. */
    private static final int MAX_COMMENT_LENGTH = 500;

    private final ClientRepository clients;
    private final ConsentRepository consents;
    private final PrepaymentFlagJournal flagJournal;
    private final DefaultDocumentVersionService documents;
    private final ClientMerger merger;
    private final CommandValidator validator;
    private final Clock clock;

    DefaultClientService(
            ClientRepository clients,
            ConsentRepository consents,
            PrepaymentFlagJournal flagJournal,
            DefaultDocumentVersionService documents,
            ClientMerger merger,
            CommandValidator validator,
            Clock clock) {
        this.clients = clients;
        this.consents = consents;
        this.flagJournal = flagJournal;
        this.documents = documents;
        this.merger = merger;
        this.validator = validator;
        this.clock = clock;
    }

    @Override
    public ClientView create(CreateClient command, Actor actor) {
        actor.requireMaster();
        validator.requireValid(command);
        var phone = Phones.normalize(command.phone()).orElse(null);
        requirePhoneFree(phone, null);
        return clients.save(ClientEntity.card(command, phone)).toView();
    }

    @Override
    public ClientView register(RegisterClient command) {
        validator.requireValid(command);
        var own = clients.findByAccountId(command.accountId());
        if (own.isPresent()) {
            return own.get().toView();
        }
        var phone = Phones.normalize(command.phone()).orElse(null);
        if (phone != null) {
            var sameNumber = clients.findByPhone(phone);
            if (sameNumber.isPresent()) {
                return attachAccount(sameNumber.get(), command);
            }
        }
        return clients.save(ClientEntity.registered(command, phone)).toView();
    }

    @Override
    public ClientView update(UUID clientId, UpdateClient command, Actor actor) {
        actor.requireMasterOrSelf(clientId);
        validator.requireValid(command);
        var client = require(clientId);
        client.apply(command);
        return client.toView();
    }

    @Override
    public ClientView changePhone(UUID clientId, String phone, Actor actor) {
        actor.requireMaster();
        var normalized = Phones.require(phone);
        var client = require(clientId);
        requirePhoneFree(normalized, clientId);
        client.setPhone(normalized);
        return client.toView();
    }

    @Override
    public void updateMasterNote(UUID clientId, String note, Actor actor) {
        actor.requireMaster();
        require(clientId).setMasterNote(note);
    }

    @Override
    public void recordConsent(UUID clientId, RecordConsent command, Actor actor) {
        actor.requireMasterOrSelf(clientId);
        validator.requireValid(command);
        if (!actor.isMaster() && command.source() != ConsentSource.SITE_CHECKBOX) {
            throw new AccessDeniedException();
        }
        var currentVersion = documents.currentVersion(command.kind().document());
        if (!currentVersion.equals(command.documentVersion())) {
            throw new ConsentVersionOutdatedException(command.kind(), currentVersion);
        }
        var client = require(clientId);
        consents.save(new ConsentEntity(
                client.id(), command.kind(), command.documentVersion(), command.source(), clock.instant()));
    }

    @Override
    public void applySystemPrepaymentChange(UUID clientId, PrepaymentFlagChange change) {
        validator.requireValid(change);
        if (MASTER_ONLY_REASONS.contains(change.reason())) {
            throw new ValidationException("reason", "reserved for the master");
        }
        flagJournal.record(
                require(clientId),
                change.required(),
                change.reason(),
                ChangedBy.SYSTEM,
                change.bookingId(),
                change.comment());
    }

    @Override
    public void setPrepaymentRequired(UUID clientId, boolean required, @Nullable String comment, Actor actor) {
        actor.requireMaster();
        if (comment != null && comment.length() > MAX_COMMENT_LENGTH) {
            throw new ValidationException("comment", "must be at most " + MAX_COMMENT_LENGTH + " characters");
        }
        var reason = required ? PrepaymentFlagReason.MASTER_SET : PrepaymentFlagReason.MASTER_WAIVED;
        flagJournal.record(require(clientId), required, reason, ChangedBy.MASTER, null, comment);
    }

    @Override
    public MergeResult merge(UUID sourceId, UUID targetId, Actor actor) {
        actor.requireMaster();
        if (sourceId.equals(targetId)) {
            throw new ValidationException("targetId", "must differ from sourceId");
        }
        return merger.merge(require(sourceId), require(targetId));
    }

    /** Карточка от мастера с тем же телефоном становится аккаунтом клиента; её данные важнее данных провайдера. */
    private ClientView attachAccount(ClientEntity existing, RegisterClient command) {
        if (existing.accountId().isPresent()) {
            throw new PhoneTakenException(existing.phone().orElseThrow());
        }
        existing.attachAccount(command.accountId());
        return existing.toView();
    }

    private void requirePhoneFree(@Nullable String phone, @Nullable UUID ownerId) {
        if (phone == null) {
            return;
        }
        clients.findByPhone(phone)
                .filter(other -> !Objects.equals(other.id(), ownerId))
                .ifPresent(other -> {
                    throw new PhoneTakenException(phone);
                });
    }

    private ClientEntity require(UUID clientId) {
        return clients.findById(clientId).orElseThrow(() -> new ClientNotFoundException(clientId));
    }
}
