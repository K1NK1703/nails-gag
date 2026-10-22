package gag.nails.api.internal.http;

/** Пути слоя, которые нужны нескольким областям сразу. */
public final class ApiPaths {

    /**
     * Адреса входа (спецификация API, §6): у них своя корзина лимита частоты, под ними живут cookie этапа входа и
     * билета.
     */
    public static final String AUTH = "/api/auth";

    /** Загрузка фото (спецификация файлов и портфолио, §6.1): у {@code POST} своя корзина лимита частоты. */
    public static final String FILES = "/api/files";

    private ApiPaths() {}
}
