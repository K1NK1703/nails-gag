package gag.nails.clients.error;

import gag.nails.clients.DocumentKind;
import gag.nails.common.error.UserFacingException;
import java.time.LocalDate;
import java.util.Map;

/** У документа уже есть редакция с этой датой начала: действующая на день должна быть одна. */
public final class DocumentVersionDateTakenException extends UserFacingException {

    public DocumentVersionDateTakenException(DocumentKind kind, LocalDate effectiveFrom) {
        super("clients.document_version_date_taken", Map.of("kind", kind, "effectiveFrom", effectiveFrom));
    }
}
