package gag.nails.clients.internal;

import gag.nails.clients.DocumentKind;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface DocumentVersionRepository extends JpaRepository<DocumentVersionEntity, UUID> {

    /** Действующая на дату редакция; при равных датах (чего не допускает БД) — последняя добавленная. */
    Optional<DocumentVersionEntity> findFirstByKindAndEffectiveFromLessThanEqualOrderByEffectiveFromDescIdDesc(
            DocumentKind kind, LocalDate date);

    List<DocumentVersionEntity> findAllByKindOrderByEffectiveFromDesc(DocumentKind kind);

    boolean existsByKindAndVersion(DocumentKind kind, String version);

    boolean existsByKindAndEffectiveFrom(DocumentKind kind, LocalDate effectiveFrom);
}
