package gag.nails.clients.model;

/** Вид согласия и документ, версия которого фиксируется вместе с ним. */
public enum ConsentKind {
    PERSONAL_DATA(DocumentKind.POLICY),
    OFFER(DocumentKind.OFFER),
    REVIEW_PUBLICATION(DocumentKind.POLICY);

    private final DocumentKind document;

    ConsentKind(DocumentKind document) {
        this.document = document;
    }

    public DocumentKind document() {
        return document;
    }
}
