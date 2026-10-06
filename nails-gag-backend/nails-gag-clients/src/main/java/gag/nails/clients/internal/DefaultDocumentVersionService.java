package gag.nails.clients.internal;

import gag.nails.clients.AddDocumentVersion;
import gag.nails.clients.DocumentKind;
import gag.nails.clients.DocumentVersionService;
import gag.nails.clients.DocumentVersionView;
import gag.nails.clients.error.DocumentVersionDateTakenException;
import gag.nails.clients.error.DocumentVersionExistsException;
import gag.nails.clients.internal.error.DocumentVersionMissingException;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.time.SalonTimeZone;
import gag.nails.common.validation.CommandValidator;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultDocumentVersionService implements DocumentVersionService {

    private final DocumentVersionRepository versions;
    private final CommandValidator validator;
    private final Clock clock;
    private final SalonTimeZone salonTimeZone;

    DefaultDocumentVersionService(
            DocumentVersionRepository versions, CommandValidator validator, Clock clock, SalonTimeZone salonTimeZone) {
        this.versions = versions;
        this.validator = validator;
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
    public DocumentVersionView addVersion(AddDocumentVersion command, Actor actor) {
        actor.requireWrite(Section.DOCUMENTS);
        validator.requireValid(command);
        var kind = command.kind();
        if (versions.existsByKindAndVersion(kind, command.version())) {
            throw new DocumentVersionExistsException(kind, command.version());
        }
        if (versions.existsByKindAndEffectiveFrom(kind, command.effectiveFrom())) {
            throw new DocumentVersionDateTakenException(kind, command.effectiveFrom());
        }
        return versions.save(new DocumentVersionEntity(kind, command.version(), command.effectiveFrom()))
                .toView();
    }

    /** Для проверки согласий внутри модуля: версия действующей редакции. */
    String currentVersion(DocumentKind kind) {
        return currentEntity(kind).version();
    }

    private DocumentVersionEntity currentEntity(DocumentKind kind) {
        var today = salonTimeZone.dayOf(clock.instant());
        return versions.findFirstByKindAndEffectiveFromLessThanEqualOrderByEffectiveFromDescIdDesc(kind, today)
                .orElseThrow(() -> new DocumentVersionMissingException(kind));
    }
}
