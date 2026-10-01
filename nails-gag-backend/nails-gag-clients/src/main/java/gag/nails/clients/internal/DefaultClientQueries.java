package gag.nails.clients.internal;

import gag.nails.clients.ClientQueries;
import gag.nails.clients.ClientView;
import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.common.actor.Actor;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultClientQueries implements ClientQueries {

    private final ClientRepository clients;

    DefaultClientQueries(ClientRepository clients) {
        this.clients = clients;
    }

    @Override
    public ClientView get(UUID clientId, Actor actor) {
        DefaultClientService.requireMasterOrSelf(actor, clientId);
        return byId(clientId).orElseThrow(() -> new ClientNotFoundException(clientId));
    }

    @Override
    public Optional<ClientView> byId(UUID clientId) {
        return clients.findById(clientId).map(ClientEntity::toView);
    }

    @Override
    public Optional<ClientView> byPhone(String phone) {
        return Phones.normalize(phone).flatMap(clients::findByPhone).map(ClientEntity::toView);
    }

    @Override
    public List<ClientView> search(String query, Actor actor) {
        actor.requireMaster();
        var pattern = "%" + query.strip().toLowerCase(Locale.ROOT) + "%";
        return clients.search(pattern).stream().map(ClientEntity::toView).toList();
    }

    @Override
    public Optional<String> masterNote(UUID clientId, Actor actor) {
        actor.requireMaster();
        return clients.findById(clientId)
                .orElseThrow(() -> new ClientNotFoundException(clientId))
                .masterNote();
    }
}
