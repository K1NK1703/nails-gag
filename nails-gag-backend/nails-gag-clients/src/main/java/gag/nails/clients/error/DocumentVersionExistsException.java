package gag.nails.clients.error;

import gag.nails.clients.DocumentKind;
import gag.nails.common.error.UserFacingException;
import java.util.Map;

/** Редакция документа с такой меткой версии уже есть. */
public final class DocumentVersionExistsException extends UserFacingException {

    public DocumentVersionExistsException(DocumentKind kind, String version) {
        super("clients.document_version_exists", Map.of("kind", kind, "version", version));
    }
}
