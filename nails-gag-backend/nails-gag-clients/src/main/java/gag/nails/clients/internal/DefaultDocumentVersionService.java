package gag.nails.clients.internal;

import gag.nails.clients.DocumentKind;
import gag.nails.clients.DocumentVersionService;
import gag.nails.clients.DocumentVersionView;
import gag.nails.clients.error.DocumentVersionExistsException;
import gag.nails.clients.internal.error.DocumentVersionMissingException;
import gag.nails.common.actor.Actor;
import gag.nails.common.time.SalonTimeZone;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultDocumentVersionService implements DocumentVersionService {

    private final DocumentVersionRepository versions;
    private final Clock clock;
    private final SalonTimeZone salonTimeZone;

    DefaultDocumentVersionService(DocumentVersionRepository versions, Clock clock, SalonTimeZone salonTimeZone) {
        this.versions = versions;
        this.clock = clock;
        this.salonTimeZone = salonTimeZone;
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentVersionView current(DocumentKind kind) {
        return currentEntity(kind).toView();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentVersionView> history(DocumentKind kind) {
        return versions.findAllByKindOrderByEffectiveFromDesc(kind).stream()
                .map(DocumentVersionEntity::toView)
                .toList();
    }

    @Override
    public DocumentVersionView addVersion(DocumentKind kind, String version, LocalDate effectiveFrom, Actor actor) {
        actor.requireMaster();
        if (versions.existsByKindAndVersion(kind, version)) {
            throw new DocumentVersionExistsException(kind, version);
        }
        return versions.save(new DocumentVersionEntity(kind, version, effectiveFrom))
                .toView();
    }

    /** Для проверки согласий внутри модуля: версия действующей редакции. */
    String currentVersion(DocumentKind kind) {
        return currentEntity(kind).version();
    }

    private DocumentVersionEntity currentEntity(DocumentKind kind) {
        var today = salonTimeZone.dayOf(clock.instant());
        return versions.findFirstByKindAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(kind, today)
                .orElseThrow(() -> new DocumentVersionMissingException(kind));
    }
}
