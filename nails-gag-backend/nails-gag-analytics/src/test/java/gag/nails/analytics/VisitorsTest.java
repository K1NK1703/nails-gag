package gag.nails.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.analytics.internal.AnalyticsProperties;
import gag.nails.analytics.internal.count.DailyCounts;
import gag.nails.analytics.internal.count.Tally;
import gag.nails.analytics.internal.visitor.Visitor;
import gag.nails.analytics.internal.visitor.Visitors;
import gag.nails.analytics.model.Metric;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Уникальные посетители дня по суточному отпечатку — на настоящих транзакциях, с уборкой своих дней (2312 год).
 */
@AnalyticsModuleTest
class VisitorsTest extends AnalyticsTestSupport {

    private static final long WAIT_SECONDS = 20;
    private static final long POLL_MILLIS = 5;

    private static final String SALT_LOCK_WAITS = """
            select count(*) from pg_stat_activity
            where datname = current_database() and pid <> pg_backend_pid()
              and wait_event_type = 'Lock' and query like '%analytics.day_salt%'""";

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    Visitors visitors;

    @Autowired
    DailyCounts counts;

    @Autowired
    AnalyticsProperties properties;

    @Test
    void sameIpAndUserAgentIsOneVisitorPerDay() {
        var day = onDay(LocalDate.of(2312, 1, 1));

        view("/");
        view("/terms");
        ingest.record(pageView("/", true, "ya.ru"), IP, BROWSER);

        assertThat(count(day, "visitors", "all")).isOne();
        assertThat(visitorRows(day)).isOne();
        assertThat(saltRows(day)).isOne();
    }

    @Test
    void otherUserAgentOrIpIsAnotherVisitor() {
        var day = onDay(LocalDate.of(2312, 1, 2));

        view("/");
        ingest.record(pageView("/", null, null), IP, IPHONE);
        ingest.record(pageView("/", null, null), "198.51.100.1", BROWSER);
        ingest.record(pageView("/", null, null), "198.51.100.1", BROWSER);

        assertThat(count(day, "visitors", "all")).isEqualTo(3);
        assertThat(visitorRows(day)).isEqualTo(3);
    }

    @Test
    void onlyPageViewsCountVisitors() {
        var day = onDay(LocalDate.of(2312, 1, 3));

        ingest.record(cta("hero"), IP, BROWSER);
        ingest.record(map(), IP, BROWSER);

        assertThat(count(day, "visitors", "all")).isZero();
        assertThat(visitorRows(day)).isZero();
        assertThat(saltRows(day)).isZero();
    }

    @Test
    void nextDayCountsAgainWithNewSalt() {
        var day = onDay(LocalDate.of(2312, 1, 4));
        view("/");
        var next = onDay(day.plusDays(1));
        view("/");

        assertThat(count(day, "visitors", "all")).isOne();
        assertThat(count(next, "visitors", "all")).isOne();
        assertThat(salt(day)).isNotEqualTo(salt(next));
        assertThat(hash(day)).isNotEqualTo(hash(next));
    }

    /** Разные записи одного адреса — один посетитель: IPv6 с нулями и без, IPv4 и IPv4-mapped IPv6. */
    @Test
    void sameAddressWrittenDifferentlyIsOneVisitor() {
        var day = onDay(LocalDate.of(2312, 1, 10));

        ingest.record(pageView("/", null, null), "2001:db8::1", BROWSER);
        ingest.record(pageView("/", null, null), "2001:0db8:0:0::1", BROWSER);
        ingest.record(pageView("/", null, null), "2001:DB8:0:0:0:0:0:1", BROWSER);
        assertThat(count(day, "visitors", "all")).isOne();

        ingest.record(pageView("/", null, null), "::ffff:203.0.113.7", BROWSER);
        ingest.record(pageView("/", null, null), "203.0.113.7", BROWSER);
        assertThat(count(day, "visitors", "all")).isEqualTo(2);
        assertThat(visitorRows(day)).isEqualTo(2);
        assertThat(count(day, "page_view", "/")).isEqualTo(5);
    }

    /**
     * Сверх предела посетителей за день ({@code max-visitors-per-day}) новый не отмечается, а
     * просмотр считается. Число посетителей дня берётся из счётчика {@code visitors/all}: тест ставит его вплотную к
     * пределу.
     */
    @Test
    void visitorsOverTheDailyLimitAreNotMarked() {
        var day = onDay(LocalDate.of(2312, 1, 12));
        long limit = properties.maxVisitorsPerDay();
        jdbc.sql("insert into analytics.daily_count (day, metric, key, count) values (:day, 'visitors', 'all', :count)")
                .param("day", day)
                .param("count", limit - 1)
                .update();

        ingest.record(pageView("/", null, null), "198.51.100.1", BROWSER);
        ingest.record(pageView("/", null, null), "198.51.100.2", BROWSER);
        ingest.record(pageView("/", null, null), "198.51.100.1", BROWSER);

        assertThat(count(day, "visitors", "all")).isEqualTo(limit);
        assertThat(visitorRows(day)).isOne();
        assertThat(count(day, "page_view", "/")).isEqualTo(3);
    }

    /** Отпечаток — {@code SHA-256(соль ‖ длина адреса ‖ байты адреса ‖ userAgent в UTF-8)}; адрес и UA не хранятся. */
    @Test
    void fingerprintIsSaltedHashOfIpAndUserAgent() throws Exception {
        var day = onDay(LocalDate.of(2312, 1, 6));
        var userAgent = BROWSER + " Ёлка";

        ingest.record(pageView("/", null, null), IP, userAgent);

        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(salt(day));
        // 203.0.113.7 — один байт длины и четыре байта адреса, не текст.
        digest.update((byte) 4);
        digest.update(new byte[] {(byte) 203, 0, 113, 7});
        digest.update(userAgent.getBytes(StandardCharsets.UTF_8));
        assertThat(hash(day)).isEqualTo(digest.digest());
        assertThat(salt(day)).hasSize(32);
    }

    /**
     * Два первых события дня одновременно. Первое (здесь — тот же путь приёма, что у {@code AnalyticsIngest}, в
     * транзакции теста) создало соль и отметило посетителя, но ещё не зафиксировано. Второе — тот же IP и браузер —
     * встаёт на вставке соли, ждёт фиксации первого и берёт его соль: соль у дня одна, посетитель — один.
     *
     * <p>У второго события {@code lock_timeout} 500 мс. Первая транзакция отпускается сразу, как только ожидание
     * видно в {@code pg_stat_activity} (опрос раз в {@value #POLL_MILLIS} мс), поэтому второе ждёт лишь шаг опроса,
     * один запрос и фиксацию первой — единицы миллисекунд, заведомо меньше 500 мс.
     */
    @Test
    void concurrentFirstEventsOfDayShareOneSalt() throws Exception {
        var day = onDay(LocalDate.of(2312, 1, 8));
        var transaction = new TransactionTemplate(transactionManager);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> transaction.executeWithoutResult(status -> {
                if (visitors.firstVisitOfDay(day, Visitor.of(IP, BROWSER).orElseThrow())) {
                    counts.increment(day, Visitors.ALL);
                }
                counts.increment(day, new Tally(Metric.PAGE_VIEW, "/"));
                held.countDown();
                await(release);
            }));
            assertThat(held.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> view("/"));
            try {
                awaitSaltLockWait();
            } finally {
                release.countDown();
            }
            first.get(WAIT_SECONDS, TimeUnit.SECONDS);
            second.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(saltRows(day)).isOne();
        assertThat(visitorRows(day)).isOne();
        assertThat(count(day, "visitors", "all")).isOne();
        assertThat(count(day, "page_view", "/")).isEqualTo(2);
    }

    private byte[] salt(LocalDate day) {
        return jdbc.sql("select salt from analytics.day_salt where day = :day")
                .param("day", day)
                .query(byte[].class)
                .single();
    }

    private byte[] hash(LocalDate day) {
        return jdbc.sql("select hash from analytics.visitor where day = :day")
                .param("day", day)
                .query(byte[].class)
                .single();
    }

    private void awaitSaltLockWait() throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (jdbc.sql(SALT_LOCK_WAITS).query(Integer.class).single() > 0) {
                return;
            }
            Thread.sleep(POLL_MILLIS);
        }
        throw new AssertionError("no event is waiting for the salt of the day");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("latch was not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
