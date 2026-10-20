package gag.nails.clients.internal.client;

import gag.nails.clients.ClientService;
import gag.nails.clients.command.CreateClient;
import gag.nails.clients.command.PrepaymentFlagChange;
import gag.nails.clients.command.RecordConsent;
import gag.nails.clients.command.RegisterClient;
import gag.nails.clients.command.UpdateClient;
import gag.nails.clients.error.ClientBlockedException;
import gag.nails.clients.error.ClientErasedException;
import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.clients.error.ConsentRequiredException;
import gag.nails.clients.error.ConsentVersionOutdatedException;
import gag.nails.clients.error.PhoneTakenException;
import gag.nails.clients.event.ClientBlocked;
import gag.nails.clients.event.ClientErased;
import gag.nails.clients.event.ConsentGiven;
import gag.nails.clients.event.ConsentWithdrawn;
import gag.nails.clients.internal.consent.ConsentEntity;
import gag.nails.clients.internal.consent.ConsentRepository;
import gag.nails.clients.internal.consent.DefaultDocumentVersionService;
import gag.nails.clients.internal.prepayment.PrepaymentFlagJournal;
import gag.nails.clients.model.ChangedBy;
import gag.nails.clients.model.ClientView;
import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentSource;
import gag.nails.clients.model.MergeResult;
import gag.nails.clients.model.PrepaymentFlagReason;
import gag.nails.clients.model.WithdrawnBy;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.InternalException;
import gag.nails.common.error.ValidationException;
import gag.nails.common.persistence.ConstraintViolations;
import gag.nails.common.text.Phones;
import gag.nails.common.validation.CommandValidator;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultClientService implements ClientService {

    /** Причины, которые ставит только мастер или объединение; правилу системы они недоступны. */
    private static final Set<PrepaymentFlagReason> MASTER_ONLY_REASONS = EnumSet.of(
            PrepaymentFlagReason.MASTER_SET, PrepaymentFlagReason.MASTER_WAIVED, PrepaymentFlagReason.MERGED);

    /** Согласия, без которых клиента нельзя обслуживать: их не отзывают. */
    private static final Set<ConsentKind> REQUIRED_CONSENTS = EnumSet.of(ConsentKind.PERSONAL_DATA, ConsentKind.OFFER);

    /** Та же граница, что у {@link PrepaymentFlagChange#comment()}. */
    private static final int MAX_COMMENT_LENGTH = 500;

    /** Та же граница, что в ограничении {@code client_block_reason_check}. */
    private static final int MAX_BLOCK_REASON_LENGTH = 500;

    /** Уникальный индекс телефона из миграции {@code 0001-init}. */
    private static final String PHONE_KEY = "client_phone_key";

    private final ClientRepository clients;
    private final ConsentRepository consents;
    private final PrepaymentFlagJournal flagJournal;
    private final DefaultDocumentVersionService documents;
    private final ClientMerger merger;
    private final CommandValidator validator;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    DefaultClientService(
            ClientRepository clients,
            ConsentRepository consents,
            PrepaymentFlagJournal flagJournal,
            DefaultDocumentVersionService documents,
            ClientMerger merger,
            CommandValidator validator,
            ApplicationEventPublisher events,
            Clock clock) {
        this.clients = clients;
        this.consents = consents;
        this.flagJournal = flagJournal;
        this.documents = documents;
        this.merger = merger;
        this.validator = validator;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public ClientView create(CreateClient command, Actor actor) {
        actor.requireWrite(Section.CLIENTS);
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
        var email = command.email();
        if (email != null && !email.isBlank()) {
            // Между двумя карточками с одной почтой не угадываем: заводится новая, дубль разрешит мастер.
            var sameEmail = clients.findAllUnregisteredByEmail(email);
            if (sameEmail.size() == 1) {
                return attachAccount(sameEmail.getFirst(), command);
            }
        }
        return clients.save(ClientEntity.registered(command, phone)).toView();
    }

    @Override
    public ClientView update(UUID clientId, UpdateClient command, Actor actor) {
        actor.requireWriteOrSelf(Section.CLIENTS, clientId);
        validator.requireValid(command);
        var client = requireLive(clientId);
        client.apply(command);
        return client.toView();
    }

    @Override
    public ClientView changePhone(UUID clientId, String phone, Actor actor) {
        actor.requireWrite(Section.CLIENTS);
        var normalized = Phones.require(phone);
        var client = requireLive(clientId);
        requirePhoneFree(normalized, clientId);
        client.setPhone(normalized);
        return client.toView();
    }

    @Override
    public ClientView changeContactPhone(UUID clientId, @Nullable String phone, Actor actor) {
        actor.requireWriteOrSelf(Section.CLIENTS, clientId);
        var normalized = Phones.normalize(phone).orElse(null);
        var client = requireLive(clientId);
        client.setContactPhone(normalized);
        return client.toView();
    }

    @Override
    public boolean confirmPhone(UUID clientId, String phone) {
        var normalized = Phones.require(phone);
        var client = requireLive(clientId);
        if (client.phone().isPresent() || clients.findByPhone(normalized).isPresent()) {
            return false;
        }
        client.setPhone(normalized);
        flushConfirmedPhone(client, normalized);
        return true;
    }

    @Override
    public void updateMasterNote(UUID clientId, String note, Actor actor) {
        actor.requireWrite(Section.CLIENTS);
        requireLive(clientId).setMasterNote(note);
    }

    @Override
    public void recordConsent(UUID clientId, RecordConsent command, Actor actor) {
        actor.requireWriteOrSelf(Section.CLIENTS, clientId);
        validator.requireValid(command);
        if (!actor.canWrite(Section.CLIENTS) && command.source() != ConsentSource.SITE_CHECKBOX) {
            throw new AccessDeniedException();
        }
        var currentVersion = documents.currentVersion(command.kind().document());
        if (!currentVersion.equals(command.documentVersion())) {
            throw new ConsentVersionOutdatedException(command.kind(), currentVersion);
        }
        var client = requireLive(clientId);
        var now = clock.instant();
        consents.save(new ConsentEntity(client.id(), command.kind(), command.documentVersion(), command.source(), now));
        events.publishEvent(new ConsentGiven(client.id(), command.kind(), now));
    }

    @Override
    public void withdrawConsent(UUID clientId, ConsentKind kind, Actor actor) {
        actor.requireWriteOrSelf(Section.CLIENTS, clientId);
        if (REQUIRED_CONSENTS.contains(kind)) {
            throw new ConsentRequiredException(kind);
        }
        var client = requireLive(clientId);
        var current = consents.findAllByClientIdAndKindAndWithdrawnAtIsNull(client.id(), kind);
        if (current.isEmpty()) {
            return;
        }
        var now = clock.instant();
        var by = actor.isClient(clientId) ? WithdrawnBy.CLIENT : WithdrawnBy.STAFF;
        current.forEach(consent -> consent.withdraw(now, by));
        events.publishEvent(new ConsentWithdrawn(client.id(), kind, now));
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
        actor.requireWrite(Section.CLIENTS);
        if (comment != null && comment.length() > MAX_COMMENT_LENGTH) {
            throw new ValidationException("comment", "must be at most " + MAX_COMMENT_LENGTH + " characters");
        }
        var reason = required ? PrepaymentFlagReason.MASTER_SET : PrepaymentFlagReason.MASTER_WAIVED;
        flagJournal.record(requireLive(clientId), required, reason, ChangedBy.MASTER, null, comment);
    }

    @Override
    public ClientView block(UUID clientId, @Nullable String reason, Actor actor) {
        actor.requireWrite(Section.CLIENTS);
        // Длина в символах (code points), как char_length в CHECK базы, а не в единицах UTF-16.
        if (reason != null && reason.codePointCount(0, reason.length()) > MAX_BLOCK_REASON_LENGTH) {
            throw new ValidationException("reason", "must be at most " + MAX_BLOCK_REASON_LENGTH + " characters");
        }
        var client = requireLive(clientId);
        var now = clock.instant();
        client.block(reason, now);
        events.publishEvent(new ClientBlocked(client.id(), client.accountId().orElse(null), now));
        return client.toView();
    }

    @Override
    public ClientView unblock(UUID clientId, Actor actor) {
        actor.requireWrite(Section.CLIENTS);
        var client = require(clientId);
        client.unblock();
        return client.toView();
    }

    @Override
    public void erase(UUID clientId) {
        var client = require(clientId);
        if (client.isErased()) {
            return;
        }
        var now = clock.instant();
        client.erase(now);
        events.publishEvent(new ClientErased(client.id(), now));
    }

    @Override
    public MergeResult merge(UUID sourceId, UUID targetId, Actor actor) {
        actor.requireWrite(Section.CLIENTS);
        if (sourceId.equals(targetId)) {
            throw new ValidationException("targetId", "must differ from sourceId");
        }
        return merger.merge(require(sourceId), require(targetId));
    }

    @Override
    public MergeResult mergeOwn(UUID otherClientId, Actor actor) {
        var ownId = actor.requireClientId();
        if (otherClientId.equals(ownId)) {
            throw new ValidationException("otherClientId", "must differ from the actor's own client");
        }
        var own = require(ownId);
        var other = require(otherClientId);
        requireNotBlocked(own);
        requireNotBlocked(other);
        return merger.mergeInto(own, other);
    }

    /**
     * Карточка с тем же телефоном или почтой становится карточкой аккаунта; её данные важнее данных
     * провайдера. Заблокированная карточка аккаунт не получает: вход для неё закрыт.
     */
    private ClientView attachAccount(ClientEntity existing, RegisterClient command) {
        if (existing.isBlocked()) {
            throw new ClientBlockedException(existing.id());
        }
        if (existing.accountId().isPresent()) {
            throw new PhoneTakenException(existing.phone().orElseThrow());
        }
        existing.attachAccount(command.accountId());
        return existing.toView();
    }

    /**
     * Два входа с одним подтверждённым номером одновременно: проверка выше у обоих прошла, уникальный
     * индекс пропустит одного. Flush здесь, чтобы нарушение всплыло в этом модуле и стало его исключением,
     * а не исключением фреймворка у модуля входа. После него транзакция PostgreSQL прервана, поэтому
     * «номер занят» нельзя вернуть как {@code false}: исключению дают всплыть.
     */
    private void flushConfirmedPhone(ClientEntity client, String phone) {
        try {
            clients.saveAndFlush(client);
        } catch (DataIntegrityViolationException e) {
            var name = ConstraintViolations.constraintName(e);
            if (name.filter(PHONE_KEY::equals).isPresent()) {
                throw new PhoneTakenException(phone);
            }
            throw new InternalException("client write violated constraint " + name.orElse("<unknown>"), e);
        }
    }

    private static void requireNotBlocked(ClientEntity client) {
        if (client.isBlocked()) {
            throw new ClientBlockedException(client.id());
        }
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

    /** Клиент, которого ещё можно менять: обезличенная карточка — только для системных отметок. */
    private ClientEntity requireLive(UUID clientId) {
        var client = require(clientId);
        if (client.isErased()) {
            throw new ClientErasedException(clientId);
        }
        return client;
    }
}
