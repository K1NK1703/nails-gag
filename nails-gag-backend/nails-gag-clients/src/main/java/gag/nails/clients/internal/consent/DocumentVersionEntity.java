package gag.nails.clients.internal.consent;

import gag.nails.clients.model.DocumentKind;
import gag.nails.clients.model.DocumentVersionView;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(schema = "clients", name = "document_version")
class DocumentVersionEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Enumerated(EnumType.STRING)
    private DocumentKind kind;

    private String version;
    private LocalDate effectiveFrom;

    protected DocumentVersionEntity() {}

    DocumentVersionEntity(DocumentKind kind, String version, LocalDate effectiveFrom) {
        this.kind = kind;
        this.version = version;
        this.effectiveFrom = effectiveFrom;
    }

    String version() {
        return version;
    }

    DocumentVersionView toView() {
        return new DocumentVersionView(id, kind, version, effectiveFrom);
    }
}
