package gag.nails.analytics.internal.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.analytics.command.RecordEvent;
import gag.nails.analytics.internal.count.Tally;
import gag.nails.analytics.model.Metric;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Ключи счётчиков, устройство и роботы — чистые функции, без Spring и базы. */
class EventKeysTest {

    private static final String SITE = EventKeys.ownHost("nails-gag.ru");

    private static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15"
            + " (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1";
    private static final String ANDROID_PHONE = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";
    private static final String ANDROID_TABLET = "Mozilla/5.0 (Linux; Android 14; SM-X710) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    private static final String IPAD = "Mozilla/5.0 (iPad; CPU OS 12_5 like Mac OS X) AppleWebKit/605.1.15"
            + " (KHTML, like Gecko) Version/12.1 Mobile/15E148 Safari/604.1";
    private static final String DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    @ParameterizedTest
    @CsvSource({
        "/, /",
        "/terms, /terms",
        "/privacy, /privacy",
        "/booking, /booking",
        "/terms/, /terms",
        "/booking?from=tg, /booking",
        "/booking/?from=tg, /booking",
        "/privacy#cookies, /privacy",
        "/?utm_source=vk, /",
        "/cabinet, other",
        "/admin/x, other",
        "/booking/extra, other",
        "/Terms, other",
        "/terms//, other",
        "terms, other",
        "'', other"
    })
    void pageKeys(String path, String key) {
        assertThat(EventKeys.page(path)).isEqualTo(key);
        assertThat(EventKeys.tally(pageView(path))).contains(new Tally(Metric.PAGE_VIEW, key));
    }

    @Test
    void pageViewNeedsPath() {
        assertThat(EventKeys.tally(new RecordEvent("page_view", null, null, null, null, null, true, "ya.ru")))
                .isEmpty();
    }

    @Test
    void pageViewTakesEntryAndReferrer() {
        assertThat(EventKeys.tally(new RecordEvent("page_view", "/", null, null, null, null, true, "ya.ru")))
                .contains(new Tally(Metric.PAGE_VIEW, "/"));
        assertThat(EventKeys.tally(new RecordEvent("page_view", "/", null, null, null, null, false, "")))
                .contains(new Tally(Metric.PAGE_VIEW, "/"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"header", "hero", "price"})
    void ctaPlaces(String place) {
        assertThat(EventKeys.tally(event("cta_click", place, null, null, null)))
                .contains(new Tally(Metric.CTA_CLICK, place));
    }

    @ParameterizedTest
    @ValueSource(strings = {"footer", "Header", "", " hero"})
    void unknownCtaPlaceIsRejected(String place) {
        assertThat(EventKeys.tally(event("cta_click", place, null, null, null))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"telegram", "channel"})
    void contactChannels(String channel) {
        assertThat(EventKeys.tally(event("contact_click", null, channel, null, null)))
                .contains(new Tally(Metric.CONTACT_CLICK, channel));
    }

    @ParameterizedTest
    @ValueSource(strings = {"whatsapp", "Telegram", ""})
    void unknownContactChannelIsRejected(String channel) {
        assertThat(EventKeys.tally(event("contact_click", null, channel, null, null)))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 42, 99})
    void galleryIndexes(int index) {
        assertThat(EventKeys.tally(event("gallery_open", null, null, index, null)))
                .contains(new Tally(Metric.GALLERY_OPEN, String.valueOf(index)));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 100, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void galleryIndexOutOfRangeIsRejected(int index) {
        assertThat(EventKeys.tally(event("gallery_open", null, null, index, null)))
                .isEmpty();
    }

    @Test
    void galleryNeedsIndex() {
        assertThat(EventKeys.tally(event("gallery_open", null, null, null, null)))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"duration", "own-design", "a", "q-1", "0123456789012345678901234567890123456789"})
    void faqIds(String id) {
        assertThat(EventKeys.tally(event("faq_open", null, null, null, id))).contains(new Tally(Metric.FAQ_OPEN, id));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "Own-Design",
                "own design",
                "own_design",
                "вопрос",
                "01234567890123456789012345678901234567890"
            })
    void faqIdOutsidePatternIsRejected(String id) {
        assertThat(EventKeys.tally(event("faq_open", null, null, null, id))).isEmpty();
    }

    /** Служебные ключи {@code other} и {@code direct} не бывают обычным значением. */
    @ParameterizedTest
    @ValueSource(strings = {"other", "direct"})
    void reservedKeysAreRejectedAsFaqIds(String id) {
        assertThat(EventKeys.tally(event("faq_open", null, null, null, id))).isEmpty();
    }

    @Test
    void mapOpenHasNoFields() {
        assertThat(EventKeys.tally(event("map_open", null, null, null, null)))
                .contains(new Tally(Metric.MAP_OPEN, "map"));
        assertThat(EventKeys.tally(event("map_open", "header", null, null, null)))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"click", "PAGE_VIEW", "Page_view", "", "visitors", "source"})
    void unknownNameIsRejected(String name) {
        assertThat(EventKeys.tally(new RecordEvent(name, "/", null, null, null, null, null, null)))
                .isEmpty();
    }

    @Test
    void missingNameIsRejected() {
        assertThat(EventKeys.tally(new RecordEvent(null, "/", null, null, null, null, null, null)))
                .isEmpty();
    }

    @Test
    void fieldOfAnotherEventIsRejected() {
        assertThat(EventKeys.tally(new RecordEvent("page_view", "/", "hero", null, null, null, null, null)))
                .isEmpty();
        assertThat(EventKeys.tally(new RecordEvent("page_view", "/", null, null, 3, null, null, null)))
                .isEmpty();
        assertThat(EventKeys.tally(new RecordEvent("cta_click", "/", "hero", null, null, null, null, null)))
                .isEmpty();
        assertThat(EventKeys.tally(new RecordEvent("cta_click", null, "hero", null, null, null, true, null)))
                .isEmpty();
        assertThat(EventKeys.tally(new RecordEvent("contact_click", null, null, "telegram", null, null, null, "ya.ru")))
                .isEmpty();
        assertThat(EventKeys.tally(new RecordEvent("gallery_open", null, null, null, 1, "duration", null, null)))
                .isEmpty();
        assertThat(EventKeys.tally(new RecordEvent("faq_open", null, null, "channel", null, "duration", null, null)))
                .isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "ya.ru, ya.ru",
        "Yandex.RU, yandex.ru",
        "www.google.com, google.com",
        "WWW.Google.Com, google.com",
        "l.instagram.com, l.instagram.com",
        "xn--80aswg.xn--p1ai, xn--80aswg.xn--p1ai",
        "t.me, t.me"
    })
    void sourceIsLowerCaseHostWithoutWww(String referrer, String key) {
        assertThat(EventKeys.source(referrer, SITE)).isEqualTo(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"nails-gag.ru", "www.nails-gag.ru", "NAILS-GAG.RU", ""})
    void ownSiteAndEmptyAreDirect(String referrer) {
        assertThat(EventKeys.source(referrer, SITE)).isEqualTo("direct");
    }

    @Test
    void missingReferrerIsDirect() {
        assertThat(EventKeys.source(null, SITE)).isEqualTo("direct");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://ya.ru/search",
                "ya.ru/",
                "ya.ru:443",
                " ya.ru",
                "-ya.ru",
                "ya-.ru",
                "ya..ru",
                ".ya.ru",
                "ya.ru.",
                "ya_ru.com",
                "сайт.рф",
                "<script>",
                "www."
            })
    void garbageIsDirect(String referrer) {
        assertThat(EventKeys.source(referrer, SITE)).isEqualTo("direct");
    }

    @Test
    void hostLongerThan64IsDirect() {
        var host64 = "a".repeat(60) + ".com";
        assertThat(EventKeys.source(host64, SITE)).isEqualTo(host64);
        assertThat(EventKeys.source("www." + host64, SITE)).isEqualTo(host64);
        assertThat(EventKeys.source("a" + host64, SITE)).isEqualTo("direct");
        assertThat(EventKeys.source("x".repeat(10_000), SITE)).isEqualTo("direct");
    }

    /** Хост без точки — не внешний сайт: {@code localhost}, служебные ключи, домен верхнего уровня. */
    @ParameterizedTest
    @ValueSource(strings = {"localhost", "other", "direct", "com", "www.com", "WWW.ru"})
    void hostWithoutDotIsDirect(String referrer) {
        assertThat(EventKeys.source(referrer, SITE)).isEqualTo("direct");
        assertThat(EventKeys.source(referrer, EventKeys.ownHost("localhost"))).isEqualTo("direct");
    }

    /** Шаблон сверяется с сырой строкой: символы, которые {@code toLowerCase} сделал бы латиницей, не проходят. */
    @ParameterizedTest
    @ValueSource(strings = {"\u212Aazan.ru", "ya.r\u0130", "\u0130nstagram.com"})
    void nonAsciiLettersAreNotFoldedIntoLatin(String referrer) {
        assertThat(EventKeys.source(referrer, SITE)).isEqualTo("direct");
    }

    @Test
    void ownHostIsLowerCaseWithoutWww() {
        assertThat(EventKeys.ownHost("WWW.Nails-Gag.RU")).isEqualTo("nails-gag.ru");
        assertThat(EventKeys.source("www.nails-gag.ru", EventKeys.ownHost("www.nails-gag.ru")))
                .isEqualTo("direct");
    }

    @Test
    void devices() {
        assertThat(Devices.of(IPHONE)).isEqualTo("mobile");
        assertThat(Devices.of(ANDROID_PHONE)).isEqualTo("mobile");
        assertThat(Devices.of(ANDROID_TABLET)).isEqualTo("tablet");
        assertThat(Devices.of(IPAD)).isEqualTo("tablet");
        assertThat(Devices.of(DESKTOP)).isEqualTo("desktop");
        assertThat(Devices.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0"))
                .isEqualTo("desktop");
        assertThat(Devices.of("Mozilla/5.0 (Android 14; Mobile; rv:132.0) Gecko/132.0 Firefox/132.0"))
                .isEqualTo("mobile");
        assertThat(Devices.of("Mozilla/5.0 (Android 14; Tablet; rv:132.0) Gecko/132.0 Firefox/132.0"))
                .isEqualTo("tablet");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
                "Mozilla/5.0 (compatible; YandexBot/3.0; +http://yandex.com/bots)",
                "TelegramBot (like TwitterBot)",
                "curl/8.7.1",
                "Wget/1.21.4",
                "python-requests/2.32.3",
                "Java/21.0.2",
                "facebookexternalhit/1.1",
                "WhatsApp/2.23.20.0",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/130.0.0.0",
                "Mozilla/5.0 (compatible; bingpreview/2.0)",
                "Mozilla/5.0 (compatible; Yahoo! Slurp)",
                "Baiduspider/2.0",
                "SomeCrawler/1.0",
                "",
                "   "
            })
    void robots(String userAgent) {
        assertThat(Robots.isRobot(userAgent)).isTrue();
    }

    @Test
    void missingUserAgentIsRobot() {
        assertThat(Robots.isRobot(null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {IPHONE, ANDROID_PHONE, ANDROID_TABLET, IPAD, DESKTOP})
    void browsersAreNotRobots(String userAgent) {
        assertThat(Robots.isRobot(userAgent)).isFalse();
    }

    private static RecordEvent pageView(String path) {
        return new RecordEvent("page_view", path, null, null, null, null, null, null);
    }

    private static RecordEvent event(String name, String place, String channel, Integer index, String id) {
        return new RecordEvent(name, null, place, channel, index, id, null, null);
    }
}
