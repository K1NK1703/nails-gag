package gag.nails.clients.internal.client;

import gag.nails.clients.ClientQueries;
import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.clients.internal.consent.ConsentEntity;
import gag.nails.clients.internal.consent.ConsentRepository;
import gag.nails.clients.internal.consent.DefaultDocumentVersionService;
import gag.nails.clients.internal.prepayment.PrepaymentFlagChangeEntity;
import gag.nails.clients.internal.prepayment.PrepaymentFlagChangeRepository;
import gag.nails.clients.model.ClientView;
import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentView;
import gag.nails.clients.model.PrepaymentFlagChangeView;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
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
        actor.requireReadOrSelf(Section.CLIENTS, clientId);
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
        actor.requireRead(Section.CLIENTS);
        var fragment = query.strip();
        var needle = Phones.prefix(fragment).orElseGet(() -> fragment.toLowerCase(Locale.ROOT));
        var pattern = "%" + LIKE_SPECIALS.matcher(needle).replaceAll("!$0") + "%";
        return clients.search(pattern).stream().map(ClientEntity::toView).toList();
    }

    @Override
    public Optional<String> masterNote(UUID clientId, Actor actor) {
        actor.requireRead(Section.CLIENTS);
        return require(clientId).masterNote();
    }

    @Override
    public Optional<String> blockReason(UUID clientId, Actor actor) {
        actor.requireRead(Section.CLIENTS);
        return require(clientId).blockReason();
    }

    @Override
    public boolean hasCurrentConsents(UUID clientId, Set<ConsentKind> kinds) {
        return kinds.stream()
                .allMatch(kind -> consents.existsByClientIdAndKindAndDocumentVersion(
                        clientId, kind, documents.currentVersion(kind.document())));
    }

    @Override
    public List<ConsentView> consents(UUID clientId, Actor actor) {
        actor.requireReadOrSelf(Section.CLIENTS, clientId);
        return consents.findAllByClientIdOrderByGivenAtDescIdDesc(clientId).stream()
                .map(ConsentEntity::toView)
                .toList();
    }

    @Override
    public List<PrepaymentFlagChangeView> prepaymentHistory(UUID clientId, Actor actor) {
        actor.requireRead(Section.CLIENTS);
        return flagChanges.findAllByClientIdOrderByChangedAtDescIdDesc(clientId).stream()
                .map(PrepaymentFlagChangeEntity::toView)
                .toList();
    }

    @Override
    public Optional<PrepaymentFlagChangeView> lastPrepaymentChange(UUID clientId) {
        return flagChanges
                .findFirstByClientIdOrderByChangedAtDescIdDesc(clientId)
                .map(PrepaymentFlagChangeEntity::toView);
    }

    private ClientEntity require(UUID clientId) {
        return clients.findById(clientId).orElseThrow(() -> new ClientNotFoundException(clientId));
    }
}
