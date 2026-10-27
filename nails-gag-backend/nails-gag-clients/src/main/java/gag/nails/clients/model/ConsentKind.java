package gag.nails.clients.model;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Вид согласия и документ, версия которого фиксируется вместе с ним. Согласие обычно действует только по
 * действующей редакции документа: новая редакция требует спросить снова. Согласие на публикацию фото действует
 * по любой редакции, пока его не отзовут ({@link #requiresCurrentVersion()}).
 */
public enum ConsentKind {
    PERSONAL_DATA(DocumentKind.POLICY),
    OFFER(DocumentKind.OFFER),
    REVIEW_PUBLICATION(DocumentKind.POLICY),
    /** Публикация фото работ клиента в портфолио; необязательное, действует до отзыва. */
    PHOTO_PUBLICATION(DocumentKind.POLICY);

    private static final Set<ConsentKind> REQUIRED_KINDS =
            Arrays.stream(values()).filter(ConsentKind::required).collect(Collectors.toUnmodifiableSet());

    private final DocumentKind document;

    ConsentKind(DocumentKind document) {
        this.document = document;
    }

    public DocumentKind document() {
        return document;
    }

    /** Обязательное: без него клиента нельзя обслуживать (записать), и отозвать его нельзя. */
    public boolean required() {
        return switch (this) {
            case PERSONAL_DATA, OFFER -> true;
            case REVIEW_PUBLICATION, PHOTO_PUBLICATION -> false;
        };
    }

    /**
     * Согласие действует, только если дано по действующей редакции документа; {@code false} — по любой
     * редакции, до отзыва.
     */
    public boolean requiresCurrentVersion() {
        return switch (this) {
            case PERSONAL_DATA, OFFER, REVIEW_PUBLICATION -> true;
            case PHOTO_PUBLICATION -> false;
        };
    }

    /** Все обязательные виды ({@link #required()}). */
    public static Set<ConsentKind> requiredKinds() {
        return REQUIRED_KINDS;
    }
}
