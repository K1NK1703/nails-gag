package gag.nails.clients.error;

import gag.nails.clients.model.DocumentKind;
import gag.nails.common.error.UserFacingException;
import java.util.Map;

/** У документа нет редакции с такой меткой версии. */
public final class DocumentVersionNotFoundException extends UserFacingException {

    public DocumentVersionNotFoundException(DocumentKind kind, String version) {
        super("clients.document_version_not_found", Map.of("kind", kind, "version", version));
    }
}
