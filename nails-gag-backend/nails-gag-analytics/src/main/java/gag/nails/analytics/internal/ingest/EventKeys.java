package gag.nails.analytics.internal.ingest;

import gag.nails.analytics.command.RecordEvent;
import gag.nails.analytics.internal.AnalyticsProperties;
import gag.nails.analytics.internal.count.Tally;
import gag.nails.analytics.model.Metric;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Ключи счётчиков из полей события — чистые функции без состояния. */
final class EventKeys {

    /** Источник, когда пришли не с другого сайта: без {@code referrer}, со своего сайта или с неверным хостом. */
    static final String DIRECT = "direct";

    static final String MAP = "map";

    /** Наибольший номер фото галереи, который считается; больший — событие отбраковывается. */
    static final int MAX_GALLERY_INDEX = 99;

    static final int MAX_HOST_LENGTH = 64;

    private static final Set<String> PAGES = Set.of("/", "/terms", "/privacy", "/booking");
    private static final Set<String> PLACES = Set.of("header", "hero", "price");
    private static final Set<String> CHANNELS = Set.of("telegram", "channel");
    private static final Pattern FAQ_ID = Pattern.compile("[a-z0-9-]{1,40}");
    private static final Pattern HOST = Pattern.compile(AnalyticsProperties.HOST);
    private static final String WWW = "www.";

    /** Поля события; поле «есть», если оно не {@code null}. */
    private enum Field {
        PATH,
        PLACE,
        CHANNEL,
        INDEX,
        ID,
        ENTRY,
        REFERRER
    }

    private EventKeys() {}

    /**
     * Счётчик, к которому относится событие, или пусто — событие отбраковывается: имя неизвестно, обязательного поля
     * нет, значение неверное или есть поле, которого у этого вида события нет.
     */
    static Optional<Tally> tally(RecordEvent event) {
        var name = event.name();
        if (name == null) {
            return Optional.empty();
        }
        var fields = fields(event);
        return switch (name) {
            case "page_view" ->
                only(fields, Field.PATH, Field.ENTRY, Field.REFERRER) && event.path() != null
                        ? Optional.of(new Tally(Metric.PAGE_VIEW, page(event.path())))
                        : Optional.empty();
            case "cta_click" -> listed(Metric.CTA_CLICK, event.place(), PLACES, only(fields, Field.PLACE));
            case "contact_click" ->
                listed(Metric.CONTACT_CLICK, event.channel(), CHANNELS, only(fields, Field.CHANNEL));
            case "gallery_open" ->
                only(fields, Field.INDEX) && galleryIndex(event.index())
                        ? Optional.of(new Tally(Metric.GALLERY_OPEN, String.valueOf(event.index())))
                        : Optional.empty();
            case "faq_open" ->
                only(fields, Field.ID)
                                && event.id() != null
                                && FAQ_ID.matcher(event.id()).matches()
                        ? Optional.of(new Tally(Metric.FAQ_OPEN, event.id()))
                        : Optional.empty();
            case "map_open" -> fields.isEmpty() ? Optional.of(new Tally(Metric.MAP_OPEN, MAP)) : Optional.empty();
            default -> Optional.empty();
        };
    }

    /**
     * Ключ просмотра страницы. Фронт шлёт {@code location.pathname}, но путь всё равно приводится: запрос
     * ({@code ?…}) и якорь ({@code #…}) отбрасываются, один завершающий {@code /} снимается ({@code /terms/} —
     * {@code /terms}). Регистр не меняется. Публичные страницы — свой ключ, всё остальное (кабинет, админка,
     * несуществующие адреса) — {@code other}: подробности чужих путей не копятся.
     */
    static String page(String path) {
        var end = path.length();
        var query = path.indexOf('?');
        if (query >= 0) {
            end = query;
        }
        var fragment = path.indexOf('#');
        if (fragment >= 0 && fragment < end) {
            end = fragment;
        }
        if (end > 1 && path.charAt(end - 1) == '/') {
            end--;
        }
        var normalized = path.substring(0, end);
        return PAGES.contains(normalized) ? normalized : Tally.OTHER;
    }

    /**
     * Ключ источника из {@code referrer} — хоста, с которого пришли: нижний регистр, без {@code www.}. Пустой, свой
     * сайт ({@code siteHost} с {@code www.} или без), не имя хоста (с портом, путём, схемой, пробелами) и длиннее
     * {@value #MAX_HOST_LENGTH} символов — {@value #DIRECT}.
     */
    static String source(@Nullable String referrer, String siteHost) {
        if (referrer == null || referrer.length() > WWW.length() + MAX_HOST_LENGTH) {
            return DIRECT;
        }
        var host = withoutWww(referrer.toLowerCase(Locale.ROOT));
        if (host.isEmpty()
                || host.length() > MAX_HOST_LENGTH
                || !HOST.matcher(host).matches()
                || host.equals(withoutWww(siteHost.toLowerCase(Locale.ROOT)))) {
            return DIRECT;
        }
        return host;
    }

    private static String withoutWww(String host) {
        return host.startsWith(WWW) ? host.substring(WWW.length()) : host;
    }

    private static Optional<Tally> listed(Metric metric, @Nullable String value, Set<String> allowed, boolean only) {
        return only && value != null && allowed.contains(value)
                ? Optional.of(new Tally(metric, value))
                : Optional.empty();
    }

    private static boolean galleryIndex(@Nullable Integer index) {
        return index != null && index >= 0 && index <= MAX_GALLERY_INDEX;
    }

    private static boolean only(Set<Field> present, Field... allowed) {
        return Set.of(allowed).containsAll(present);
    }

    private static Set<Field> fields(RecordEvent event) {
        var present = EnumSet.noneOf(Field.class);
        if (event.path() != null) {
            present.add(Field.PATH);
        }
        if (event.place() != null) {
            present.add(Field.PLACE);
        }
        if (event.channel() != null) {
            present.add(Field.CHANNEL);
        }
        if (event.index() != null) {
            present.add(Field.INDEX);
        }
        if (event.id() != null) {
            present.add(Field.ID);
        }
        if (event.entry() != null) {
            present.add(Field.ENTRY);
        }
        if (event.referrer() != null) {
            present.add(Field.REFERRER);
        }
        return present;
    }
}
