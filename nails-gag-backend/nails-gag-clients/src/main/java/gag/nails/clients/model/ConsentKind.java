package gag.nails.clients.model;

/**
 * Вид согласия и документ, версия которого фиксируется вместе с ним. Согласие обычно действует только по
 * действующей редакции документа: новая редакция требует спросить снова. Согласие на публикацию фото действует
 * по любой редакции, пока клиент его не отзовёт ({@link #requiresCurrentVersion()}).
 */
public enum ConsentKind {
    PERSONAL_DATA(DocumentKind.POLICY, true),
    OFFER(DocumentKind.OFFER, true),
    REVIEW_PUBLICATION(DocumentKind.POLICY, true),
    /** Публикация фото работ клиента в портфолио; необязательное, действует до отзыва. */
    PHOTO_PUBLICATION(DocumentKind.POLICY, false);

    private final DocumentKind document;
    private final boolean requiresCurrentVersion;

    ConsentKind(DocumentKind document, boolean requiresCurrentVersion) {
        this.document = document;
        this.requiresCurrentVersion = requiresCurrentVersion;
    }

    public DocumentKind document() {
        return document;
    }

    /**
     * Согласие действует, только если дано по действующей редакции документа; {@code false} — по любой
     * редакции, до отзыва.
     */
    public boolean requiresCurrentVersion() {
        return requiresCurrentVersion;
    }
}
