package gag.nails.clients.internal.error;

import gag.nails.clients.model.DocumentKind;
import gag.nails.common.error.InternalException;

/** В базе нет ни одной действующей редакции документа — нарушение стартовых данных, не ошибка пользователя. */
public final class DocumentVersionMissingException extends InternalException {

    public DocumentVersionMissingException(DocumentKind kind) {
        super("no current document version for " + kind);
    }
}
