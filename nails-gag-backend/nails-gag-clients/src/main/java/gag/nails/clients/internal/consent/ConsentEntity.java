package gag.nails.clients.internal.consent;

import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentSource;
import gag.nails.clients.model.ConsentView;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(schema = "clients", name = "consent")
public class ConsentEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID clientId;

    @Enumerated(EnumType.STRING)
    private ConsentKind kind;

    private String documentVersion;
    private Instant givenAt;

    @Enumerated(EnumType.STRING)
    private ConsentSource source;

    protected ConsentEntity() {}

    public ConsentEntity(
            UUID clientId, ConsentKind kind, String documentVersion, ConsentSource source, Instant givenAt) {
        this.clientId = clientId;
        this.kind = kind;
        this.documentVersion = documentVersion;
        this.source = source;
        this.givenAt = givenAt;
    }

    public ConsentView toView() {
        return new ConsentView(kind, documentVersion, givenAt, source);
    }
}
