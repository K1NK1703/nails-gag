package gag.nails.clients.internal;

import gag.nails.clients.DocumentKind;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface DocumentVersionRepository extends JpaRepository<DocumentVersionEntity, UUID> {

    Optional<DocumentVersionEntity> findFirstByKindAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            DocumentKind kind, LocalDate date);

    List<DocumentVersionEntity> findAllByKindOrderByEffectiveFromDesc(DocumentKind kind);

    boolean existsByKindAndVersion(DocumentKind kind, String version);
}
