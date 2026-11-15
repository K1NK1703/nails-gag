package gag.nails.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import gag.nails.analytics.command.RecordEvent;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Приём событий: счётчики дня, отбраковка, день салона, предел ключей, источник и устройство, сбой записи, пределы
 * ожидания. Дни — 2311 год, свои у каждого теста.
 */
@AnalyticsModuleTest
@ExtendWith(OutputCaptureExtension.class)
class AnalyticsIngestTest extends AnalyticsTestSupport {

    private static final String LOCK_HOME_VIEWS = """
            select count from analytics.daily_count
            where day = :day and metric = 'page_view' and key = '/' for update""";

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void everyEventCountsItsOwnCounterOfTheDay() {
        var day = onDay(LocalDate.of(2311, 1, 1));

        view("/");
        view("/");
        view("/booking");
        ingest.record(cta("hero"), IP, BROWSER);
        ingest.record(contact("telegram"), IP, BROWSER);
        ingest.record(gallery(7), IP, BROWSER);
        ingest.record(faq("own-design"), IP, BROWSER);
        ingest.record(map(), IP, BROWSER);

        assertThat(counts(day, "page_view")).containsOnly(entry("/", 2), entry("/booking", 1));
        assertThat(counts(day, "cta_click")).containsOnly(entry("hero", 1));
        assertThat(counts(day, "contact_click")).containsOnly(entry("telegram", 1));
        assertThat(counts(day, "gallery_open")).containsOnly(entry("7", 1));
        assertThat(counts(day, "faq_open")).containsOnly(entry("own-design", 1));
        assertThat(counts(day, "map_open")).containsOnly(entry("map", 1));
        assertThat(counts(day, "visitors")).containsOnly(entry("all", 1));
        assertThat(counts(day, "source")).isEmpty();
        assertThat(counts(day, "device")).isEmpty();
    }

    @Test
    void privatePathsCountAsOther() {
        var day = onDay(LocalDate.of(2311, 1, 2));

        view("/cabinet");
        view("/admin/x");
        view("/terms/");
        view("/privacy?from=footer");

        assertThat(counts(day, "page_view")).containsOnly(entry("other", 2), entry("/terms", 1), entry("/privacy", 1));
    }

    @Test
    void unknownNameFieldOrValueCountsNothing() {
        var day = onDay(LocalDate.of(2311, 1, 3));

        ingest.record(new RecordEvent("click", "/", null, null, null, null, null, null), IP, BROWSER);
        ingest.record(new RecordEvent(null, "/", null, null, null, null, null, null), IP, BROWSER);
        ingest.record(new RecordEvent("page_view", "/", "hero", null, null, null, null, null), IP, BROWSER);
        ingest.record(new RecordEvent("page_view", null, null, null, null, null, true, "ya.ru"), IP, BROWSER);
        ingest.record(new RecordEvent("map_open", null, null, null, 1, null, null, null), IP, BROWSER);
        ingest.record(cta("footer"), IP, BROWSER);
        ingest.record(contact("whatsapp"), IP, BROWSER);
        ingest.record(gallery(100), IP, BROWSER);
        ingest.record(gallery(-1), IP, BROWSER);
        ingest.record(faq("Own Design"), IP, BROWSER);

        assertThat(rows(day)).isZero();
    }

    @Test
    void robotCountsNothing() {
        var day = onDay(LocalDate.of(2311, 1, 4));

        var googlebot = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";
        ingest.record(pageView("/", true, "google.com"), IP, googlebot);
        ingest.record(pageView("/", true, null), IP, "TelegramBot (like TwitterBot)");
        ingest.record(cta("hero"), IP, "curl/8.7.1");
        ingest.record(pageView("/", null, null), IP, "");
        ingest.record(pageView("/", null, null), IP, null);

        assertThat(rows(day)).isZero();
    }

    @Test
    void dayIsTheSalonDayAroundMidnight() {
        var day = take(LocalDate.of(2311, 1, 5));
        var next = take(day.plusDays(1));

        at(day, LocalTime.of(23, 59));
        view("/");
        // 00:01 по Москве — ещё 21:01 предыдущего дня по UTC: день считается по поясу салона.
        at(next, LocalTime.of(0, 1));
        view("/terms");

        assertThat(counts(day, "page_view")).containsOnly(entry("/", 1));
        assertThat(counts(next, "page_view")).containsOnly(entry("/terms", 1));
        assertThat(count(day, "visitors", "all")).isOne();
        assertThat(count(next, "visitors", "all")).isOne();
    }

    @Test
    void newKeysOverTheLimitGoToOther() {
        var day = onDay(LocalDate.of(2311, 1, 11));

        IntStream.range(0, 50).forEach(i -> ingest.record(gallery(i), IP, BROWSER));
        ingest.record(gallery(50), IP, BROWSER);
        ingest.record(gallery(99), IP, BROWSER);
        ingest.record(gallery(7), IP, BROWSER);

        var keys = counts(day, "gallery_open");
        assertThat(keys).hasSize(51).containsEntry("other", 2L).containsEntry("7", 2L);
        assertThat(keys).doesNotContainKeys("50", "99");
        // Предел у каждой метрики свой.
        ingest.record(faq("duration"), IP, BROWSER);
        assertThat(counts(day, "faq_open")).containsOnly(entry("duration", 1));
    }

    /**
     * Предел считается без {@code other}: {@code other}, который уже есть, не отнимает места у обычных ключей, а сверх
     * предела {@code other} растёт дальше, не упираясь сам.
     */
    @Test
    void otherIsNotCountedInTheLimit() {
        var day = onDay(LocalDate.of(2311, 1, 7));
        jdbc.sql("insert into analytics.daily_count (day, metric, key, count) values (:day, 'faq_open', 'other', 1)")
                .param("day", day)
                .update();

        IntStream.range(0, 60).forEach(i -> ingest.record(faq("q-" + i), IP, BROWSER));

        var keys = counts(day, "faq_open");
        assertThat(keys).hasSize(51).containsEntry("other", 11L).containsKeys("q-0", "q-49");
        assertThat(keys).doesNotContainKey("q-50");
    }

    @Test
    void sourceAndDeviceOnlyOnEntry() {
        var day = onDay(LocalDate.of(2311, 1, 8));

        ingest.record(pageView("/", null, "ya.ru"), IP, IPHONE);
        ingest.record(pageView("/", false, "ya.ru"), IP, IPHONE);
        assertThat(counts(day, "source")).isEmpty();
        assertThat(counts(day, "device")).isEmpty();

        ingest.record(pageView("/", true, "www.Yandex.ru"), IP, IPHONE);
        ingest.record(pageView("/booking", true, null), IP, BROWSER);
        ingest.record(pageView("/", true, "nails-gag.ru"), IP, BROWSER);
        ingest.record(pageView("/", true, ""), IP, IPHONE);

        assertThat(counts(day, "source")).containsOnly(entry("yandex.ru", 1), entry("direct", 3));
        assertThat(counts(day, "device")).containsOnly(entry("mobile", 2), entry("desktop", 2));
        assertThat(count(day, "page_view", "/")).isEqualTo(5);
    }

    /**
     * Сбой базы посреди записи: счётчик устройства заранее на пределе {@code bigint}, и прибавление к нему — последнее
     * в транзакции события — падает после посетителя, просмотра и источника. Наружу ничего не выходит, лог без IP и
     * {@code User-Agent}, а транзакция откачена целиком — даже соль дня, созданная этим событием.
     */
    @Test
    void writeFailureDoesNotEscapeAndLeavesNoPartialCounts(CapturedOutput output) {
        var day = onDay(LocalDate.of(2311, 1, 9));
        overflow(day, "device", "mobile");

        assertThatCode(() -> ingest.record(pageView("/", true, "ya.ru"), IP, IPHONE))
                .doesNotThrowAnyException();

        assertThat(counts(day, "device")).containsOnly(entry("mobile", Long.MAX_VALUE));
        assertThat(counts(day, "page_view")).isEmpty();
        assertThat(counts(day, "visitors")).isEmpty();
        assertThat(counts(day, "source")).isEmpty();
        assertThat(visitorRows(day)).isZero();
        assertThat(saltRows(day)).isZero();
        assertThat(output.getAll())
                .contains("analytics")
                .contains("lost")
                .doesNotContain(IP)
                .doesNotContain(IPHONE);

        // Следующие события пишутся как обычно.
        ingest.record(pageView("/", true, "ya.ru"), IP, BROWSER);
        assertThat(count(day, "page_view", "/")).isOne();
        assertThat(count(day, "visitors", "all")).isOne();
    }

    /**
     * Вызов изнутри чужой транзакции: сбой приёма её не прерывает (запросы после него идут, фиксация проходит), а её
     * откат не отменяет посчитанного — приём пишет в своей транзакции.
     */
    @Test
    void callerTransactionIsNeitherBrokenNorRolledBackWith() {
        var day = onDay(LocalDate.of(2311, 1, 10));
        overflow(day, "map_open", "map");
        var caller = new TransactionTemplate(transactionManager);

        assertThatCode(() -> caller.executeWithoutResult(status -> {
                    ingest.record(map(), IP, BROWSER);
                    assertThat(jdbc.sql("select 1").query(Integer.class).single())
                            .isOne();
                }))
                .doesNotThrowAnyException();

        caller.executeWithoutResult(status -> {
            view("/");
            status.setRollbackOnly();
        });
        assertThat(count(day, "page_view", "/")).isOne();
    }

    /** Адрес — только IP-литерал: имя хоста не разрешается через DNS, событие отбраковывается. */
    @Test
    void nonLiteralIpCountsNothing() {
        var day = onDay(LocalDate.of(2311, 1, 12));

        ingest.record(pageView("/", true, "ya.ru"), "example.com", BROWSER);
        ingest.record(cta("hero"), "localhost", BROWSER);
        ingest.record(map(), "", BROWSER);
        ingest.record(map(), "203.0.113.7:443", BROWSER);

        assertThat(rows(day)).isZero();
    }

    /**
     * Ожидание блокировки обрывается за {@code lock_timeout}: строку просмотров {@code /} за день держит чужая
     * транзакция, а приём возвращается быстро, без исключения, и ничего не записывает — ни просмотра, ни нового
     * посетителя, отмеченного до ожидания.
     */
    @Test
    void lockWaitIsCutShort() {
        var day = onDay(LocalDate.of(2311, 1, 13));
        view("/");
        var holder = new TransactionTemplate(transactionManager);

        holder.executeWithoutResult(status -> {
            jdbc.sql(LOCK_HOME_VIEWS).param("day", day).query(Long.class).single();
            assertTimeoutPreemptively(
                    Duration.ofMillis(1500), () -> ingest.record(pageView("/", null, null), "198.51.100.9", BROWSER));
        });

        assertThat(count(day, "page_view", "/")).isOne();
        assertThat(count(day, "visitors", "all")).isOne();
        assertThat(visitorRows(day)).isOne();
    }

    private void overflow(LocalDate day, String metric, String key) {
        jdbc.sql("insert into analytics.daily_count (day, metric, key, count) values (:day, :metric, :key, :count)")
                .param("day", day)
                .param("metric", metric)
                .param("key", key)
                .param("count", Long.MAX_VALUE)
                .update();
    }

    private static Map.Entry<String, Long> entry(String key, long count) {
        return Map.entry(key, count);
    }
}
