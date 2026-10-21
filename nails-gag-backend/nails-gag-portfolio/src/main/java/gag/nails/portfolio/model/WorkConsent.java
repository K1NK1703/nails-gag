package gag.nails.portfolio.model;

/** Состояние согласия на публикацию для работы — для админки. */
public enum WorkConsent {

    /** Работа клиента, согласие на публикацию фото есть. */
    GIVEN,

    /** Работа клиента, согласия нет или оно отозвано: работа не публикуется, даже видимая. */
    MISSING,

    /** Согласие получено вне сайта ({@link WorkSource#CLIENT_OUTSIDE_SITE}). */
    OUTSIDE_SITE,

    /** Согласие не нужно ({@link WorkSource#EXAMPLE}). */
    NOT_NEEDED
}
