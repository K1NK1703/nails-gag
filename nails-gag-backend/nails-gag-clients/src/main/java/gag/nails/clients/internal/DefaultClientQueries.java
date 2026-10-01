package gag.nails.clients.internal;

import gag.nails.clients.ClientQueries;
import gag.nails.clients.ClientView;
import gag.nails.clients.ConsentKind;
import gag.nails.clients.ConsentView;
import gag.nails.clients.PrepaymentFlagChangeView;
import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.common.actor.Actor;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultClientQueries implements ClientQueries {

    /** Спецсимволы LIKE и сам символ экранирования из {@link ClientRepository#search}. */
    private static final Pattern LIKE_SPECIALS = Pattern.compile("[%_!]");

    private final ClientRepository clients;
    private final ConsentRepository consents;
    private final PrepaymentFlagChangeRepository flagChanges;
    private final DefaultDocumentVersionService documents;

    DefaultClientQueries(
            ClientRepository clients,
            ConsentRepository consents,
            PrepaymentFlagChangeRepository flagChanges,
            DefaultDocumentVersionService documents) {
        this.clients = clients;
        this.consents = consents;
        this.flagChanges = flagChanges;
        this.documents = documents;
    }

    @Override
    public ClientView get(UUID clientId, Actor actor) {
        actor.requireMasterOrSelf(clientId);
        return byId(clientId).orElseThrow(() -> new ClientNotFoundException(clientId));
    }

    @Override
    public Optional<ClientView> byId(UUID clientId) {
        return clients.findById(clientId).map(ClientEntity::toView);
    }

    @Override
    public Optional<ClientView> byPhone(String phone) {
        return Phones.parse(phone).flatMap(clients::findByPhone).map(ClientEntity::toView);
    }

    @Override
    public List<ClientView> search(String query, Actor actor) {
        actor.requireMaster();
        var fragment = query.strip();
        var needle = Phones.prefix(fragment).orElseGet(() -> fragment.toLowerCase(Locale.ROOT));
        var pattern = "%" + LIKE_SPECIALS.matcher(needle).replaceAll("!$0") + "%";
        return clients.search(pattern).stream().map(ClientEntity::toView).toList();
    }

    @Override
    public Optional<String> masterNote(UUID clientId, Actor actor) {
        actor.requireMaster();
        return clients.findById(clientId)
                .orElseThrow(() -> new ClientNotFoundException(clientId))
                .masterNote();
    }

    @Override
    public boolean hasCurrentConsents(UUID clientId, Set<ConsentKind> kinds) {
        return kinds.stream()
                .allMatch(kind -> consents.existsByClientIdAndKindAndDocumentVersion(
                        clientId, kind, documents.currentVersion(kind.document())));
    }

    @Override
    public List<ConsentView> consents(UUID clientId, Actor actor) {
        actor.requireMasterOrSelf(clientId);
        return consents.findAllByClientIdOrderByGivenAtDescIdDesc(clientId).stream()
                .map(ConsentEntity::toView)
                .toList();
    }

    @Override
    public List<PrepaymentFlagChangeView> prepaymentHistory(UUID clientId, Actor actor) {
        actor.requireMaster();
        return flagChanges.findAllByClientIdOrderByChangedAtDescIdDesc(clientId).stream()
                .map(PrepaymentFlagChangeEntity::toView)
                .toList();
    }
}
