package gag.nails.clients.internal;

import gag.nails.clients.ClientService;
import gag.nails.clients.ClientView;
import gag.nails.clients.CreateClient;
import gag.nails.clients.RegisterClient;
import gag.nails.clients.UpdateClient;
import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.clients.error.PhoneTakenException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.validation.CommandValidator;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultClientService implements ClientService {

    private final ClientRepository clients;
    private final CommandValidator validator;

    DefaultClientService(ClientRepository clients, CommandValidator validator) {
        this.clients = clients;
        this.validator = validator;
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
        var phone = Phones.normalize(command.phone()).orElse(null);
        if (phone != null) {
            var existing = clients.findByPhone(phone);
            if (existing.isPresent()) {
                return attachAccount(existing.get(), command);
            }
        }
        return clients.save(ClientEntity.registered(command, phone)).toView();
    }

    @Override
    public ClientView update(UUID clientId, UpdateClient command, Actor actor) {
        requireMasterOrSelf(actor, clientId);
        validator.requireValid(command);
        var client = require(clientId);
        client.apply(command);
        return client.toView();
    }

    @Override
    public ClientView changePhone(UUID clientId, String phone, Actor actor) {
        actor.requireMaster();
        var normalized = Phones.require(phone);
        requirePhoneFree(normalized, clientId);
        var client = require(clientId);
        client.setPhone(normalized);
        return client.toView();
    }

    @Override
    public void updateMasterNote(UUID clientId, String note, Actor actor) {
        actor.requireMaster();
        require(clientId).setMasterNote(note.isBlank() ? null : note.strip());
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
        clients.findByPhone(phone).filter(other -> !other.id().equals(ownerId)).ifPresent(other -> {
            throw new PhoneTakenException(phone);
        });
    }

    private ClientEntity require(UUID clientId) {
        return clients.findById(clientId).orElseThrow(() -> new ClientNotFoundException(clientId));
    }

    static void requireMasterOrSelf(Actor actor, UUID clientId) {
        if (!actor.isMaster() && !actor.isClient(clientId)) {
            throw new AccessDeniedException();
        }
    }
}
