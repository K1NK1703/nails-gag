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
import gag.nails.clients.model.ConsentWithdrawalView;
import gag.nails.clients.model.PrepaymentFlagChangeView;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.paging.Paged;
import gag.nails.common.paging.Paging;
import gag.nails.common.text.Phones;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultClientQueries implements ClientQueries {

    /** Спецсимволы LIKE и сам символ экранирования из {@link ClientRepository#SEARCH}. */
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
    public Optional<ClientView> byEmail(String email) {
        if (email.isBlank()) {
            return Optional.empty();
        }
        var found = clients.findAllUnregisteredByEmail(email.strip());
        return found.size() == 1 ? Optional.of(found.getFirst().toView()) : Optional.empty();
    }

    @Override
    public Optional<ClientView> byAccountId(UUID accountId) {
        return clients.findByAccountId(accountId).map(ClientEntity::toView);
    }

    @Override
    public List<ClientView> search(String query, Actor actor) {
        actor.requireRead(Section.CLIENTS);
        return clients.search(searchPattern(query)).stream()
                .map(ClientEntity::toView)
                .toList();
    }

    @Override
    public Paged<ClientView> search(String query, Paging paging, Actor actor) {
        actor.requireRead(Section.CLIENTS);
        var page = clients.search(searchPattern(query), PageRequest.of(paging.page(), paging.size()));
        return Paged.of(page.getContent(), paging, page.getTotalElements()).map(ClientEntity::toView);
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
        return kinds.stream().allMatch(kind -> hasCurrentConsent(clientId, kind));
    }

    private boolean hasCurrentConsent(UUID clientId, ConsentKind kind) {
        if (!kind.requiresCurrentVersion()) {
            return consents.existsByClientIdAndKindAndWithdrawnAtIsNull(clientId, kind);
        }
        return consents.existsByClientIdAndKindAndDocumentVersionAndWithdrawnAtIsNull(
                clientId, kind, documents.currentVersion(kind.document()));
    }

    @Override
    public List<ConsentView> consents(UUID clientId, Actor actor) {
        actor.requireReadOrSelf(Section.CLIENTS, clientId);
        return consents.findAllByClientIdOrderByGivenAtDescIdDesc(clientId).stream()
                .map(ConsentEntity::toView)
                .toList();
    }

    @Override
    public List<ConsentWithdrawalView> consentWithdrawals(UUID clientId, Actor actor) {
        actor.requireReadOrSelf(Section.CLIENTS, clientId);
        return consents.findWithdrawals(clientId);
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

    /** Шаблон LIKE для {@link ClientRepository#SEARCH}: начало телефона или фрагмент имени в нижнем регистре. */
    private static String searchPattern(String query) {
        var fragment = query.strip();
        var needle = Phones.prefix(fragment).orElseGet(() -> fragment.toLowerCase(Locale.ROOT));
        return "%" + LIKE_SPECIALS.matcher(needle).replaceAll("!$0") + "%";
    }

    private ClientEntity require(UUID clientId) {
        return clients.findById(clientId).orElseThrow(() -> new ClientNotFoundException(clientId));
    }
}
