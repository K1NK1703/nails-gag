package gag.nails.clients.internal.consent;

import gag.nails.clients.model.DocumentKind;
import gag.nails.clients.model.DocumentVersionText;
import gag.nails.clients.model.DocumentVersionView;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

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

    /** Пусто только у стартовых редакций из {@code 0002-seed}: их завели до хранения текста. */
    private @Nullable String text;

    protected DocumentVersionEntity() {}

    DocumentVersionEntity(DocumentKind kind, String version, LocalDate effectiveFrom, String text) {
        this.kind = kind;
        this.version = version;
        this.effectiveFrom = effectiveFrom;
        this.text = text;
    }

    DocumentVersionView toView() {
        return new DocumentVersionView(id, kind, version, effectiveFrom);
    }

    DocumentVersionText toVersionText() {
        return new DocumentVersionText(toView(), text);
    }
}
