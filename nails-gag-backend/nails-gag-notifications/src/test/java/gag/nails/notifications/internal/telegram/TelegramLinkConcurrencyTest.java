package gag.nails.notifications.internal.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.notifications.internal.preference.AccountChannels;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Привязка по {@code /start} и удаление аккаунта ({@link AccountChannels#forget}) встречаются на строке ссылки: обе
 * начинают с неё (порядок модуля «токен → чат → настройки»). Кто первый взял строку, тот и определяет исход, но
 * осиротевшей привязки чата к удалённому аккаунту не остаётся ни в одном порядке. Порядок задаётся ожиданием
 * блокировки: вторая транзакция гарантированно встаёт на блокировку первой (видно в {@code pg_stat_activity}).
 */
class TelegramLinkConcurrencyTest extends TelegramTestSupport {

    private static final long WAIT_SECONDS = 20;

    @Autowired
    AccountChannels accountChannels;

    @Autowired
    TransactionTemplate transaction;

    @Test
    void startWaitingForAccountDeletionFindsNoToken() throws Exception {
        var anna = account();
        var chat = chat();
        var start = privateMessage(chat, "/start " + linkToken(anna));

        whileHeld(() -> accountChannels.forget(anna.accountId()), () -> updates.handle(start));

        assertThat(chatOf(anna)).isEmpty();
        assertThat(accountOf(chat)).isEmpty();
        assertThat(replies(chat)).containsExactly(EXPIRED);
    }

    @Test
    void accountDeletionWaitingForStartRemovesTheNewBinding() throws Exception {
        var anna = account();
        var chat = chat();
        var start = privateMessage(chat, "/start " + linkToken(anna));

        whileHeld(() -> updates.handle(start), () -> accountChannels.forget(anna.accountId()));

        assertThat(chatOf(anna)).isEmpty();
        assertThat(accountOf(chat)).isEmpty();
        assertThat(linkTokens(anna)).isZero();
    }

    /**
     * {@code holder} в своей транзакции берёт строку ссылки и держит её, пока {@code contender} (в своей транзакции) не
     * встанет на её блокировку; затем {@code holder} фиксируется, и {@code contender} доделывает своё.
     */
    private void whileHeld(Runnable holder, Runnable contender) throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holding = executor.submit(() -> transaction.executeWithoutResult(status -> {
                holder.run();
                held.countDown();
                await(release);
            }));
            try {
                assertThat(held.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                var contending = executor.submit(() -> transaction.executeWithoutResult(status -> contender.run()));
                awaitLockWait();
                release.countDown();
                holding.get(WAIT_SECONDS, TimeUnit.SECONDS);
                contending.get(WAIT_SECONDS, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
    }

    private void awaitLockWait() throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            var waiting = jdbc.sql("""
                            select count(*) from pg_stat_activity
                            where datname = current_database() and pid <> pg_backend_pid()
                              and wait_event_type = 'Lock' and query like '%notifications.link_token%'
                            """).query(Integer.class).single();
            if (waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("nobody waits for the link token row");
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
