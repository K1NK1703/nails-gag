package gag.nails.analytics.internal.ingest;

/**
 * Тип устройства по {@code User-Agent} — ключ метрики {@code device}. Признаки с учётом регистра, как их пишут
 * браузеры. iPad с iPadOS 13 и новее представляется компьютером Mac и считается {@code desktop}: отличить его по
 * заголовку нельзя.
 */
final class Devices {

    static final String MOBILE = "mobile";
    static final String TABLET = "tablet";
    static final String DESKTOP = "desktop";

    private Devices() {}

    /** {@code tablet}: iPad, «Tablet» или Android без «Mobile»; {@code mobile}: «Mobi», iPhone или Android. */
    static String of(String userAgent) {
        var android = userAgent.contains("Android");
        if (userAgent.contains("iPad") || userAgent.contains("Tablet") || (android && !userAgent.contains("Mobile"))) {
            return TABLET;
        }
        if (userAgent.contains("Mobi") || userAgent.contains("iPhone") || android) {
            return MOBILE;
        }
        return DESKTOP;
    }
}
