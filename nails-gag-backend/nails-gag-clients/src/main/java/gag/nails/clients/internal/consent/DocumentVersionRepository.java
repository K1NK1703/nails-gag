package gag.nails.clients.internal.consent;

import gag.nails.clients.model.DocumentKind;
import gag.nails.clients.model.DocumentVersionView;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface DocumentVersionRepository extends JpaRepository<DocumentVersionEntity, UUID> {

    /**
     * Представление без текста: текст до 200 000 символов, а действующую редакцию читает каждая проверка
     * согласий. Полностью сущность с текстом загружает только {@link #findByKindAndVersion}.
     */
    String VIEW = """
            select new gag.nails.clients.model.DocumentVersionView(d.id, d.kind, d.version, d.effectiveFrom)
            from DocumentVersionEntity d
            """;

    /**
     * Действующая на дату редакция; при равных датах (чего не допускает БД) — последняя добавленная.
     * {@code limit} — {@code Limit.of(1)}.
     */
    @Query(VIEW + "where d.kind = :kind and d.effectiveFrom <= :date order by d.effectiveFrom desc, d.id desc")
    Optional<DocumentVersionView> findEffective(DocumentKind kind, LocalDate date, Limit limit);

    /** Все редакции документа, новые первыми. */
    @Query(VIEW + "where d.kind = :kind order by d.effectiveFrom desc")
    List<DocumentVersionView> findHistory(DocumentKind kind);

    Optional<DocumentVersionEntity> findByKindAndVersion(DocumentKind kind, String version);

    boolean existsByKindAndVersion(DocumentKind kind, String version);

    boolean existsByKindAndEffectiveFrom(DocumentKind kind, LocalDate effectiveFrom);
}
