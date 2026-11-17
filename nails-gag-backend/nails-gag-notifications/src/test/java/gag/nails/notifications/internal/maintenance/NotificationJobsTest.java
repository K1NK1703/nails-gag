package gag.nails.notifications.internal.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gag.nails.common.error.InternalException;
import gag.nails.notifications.NotificationMaintenance;
import gag.nails.notifications.internal.dispatch.Dispatcher;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Сбой одной задачи пишется в лог и не выходит наружу: планировщик запустит её снова, старт приложения не прерывается,
 * а сбой уборки не мешает проверке реестра. Что задачи срабатывают на старте — {@code NotificationJobsStartupTest}.
 */
@ExtendWith(OutputCaptureExtension.class)
class NotificationJobsTest {

    private final Dispatcher dispatcher = mock(Dispatcher.class);
    private final NotificationMaintenance maintenance = mock(NotificationMaintenance.class);
    private final EventPublicationRetries publications = mock(EventPublicationRetries.class);
    private final NotificationJobs jobs = new NotificationJobs(dispatcher, maintenance, publications);

    @Test
    void dispatchFailureIsLoggedNotThrown() {
        doThrow(new InternalException("dispatch failed")).when(dispatcher).run();

        assertThatCode(jobs::dispatch).doesNotThrowAnyException();
    }

    @Test
    void purgeFailureAfterStartIsLoggedAndPublicationsAreStillChecked() {
        when(maintenance.purgeExpired()).thenThrow(new InternalException("purge failed"));

        assertThatCode(jobs::purgeExpiredAfterStart).doesNotThrowAnyException();
        verify(publications).warnAboutStale();
    }

    @Test
    void publicationsCheckFailureIsLoggedNotThrown() {
        when(publications.warnAboutStale()).thenThrow(new InternalException("check failed"));

        assertThatCode(jobs::purgeExpired).doesNotThrowAnyException();
        verify(maintenance).purgeExpired();
    }

    @Test
    void resubmissionFailureIsLoggedNotThrown() {
        doThrow(new InternalException("resubmission failed")).when(publications).resubmitFailed();

        assertThatCode(jobs::resubmitFailedPublications).doesNotThrowAnyException();
        verify(publications).resubmitFailed();
    }

    /** Ошибка базы, даже обёрнутая, — в логе только классы и SQLState, без текста драйвера и стека. */
    @Test
    void databaseFailureIsLoggedWithoutDriverText(CapturedOutput output) {
        var driver = new SQLException("Key (email)=(anna@example.com) already exists", "23505");
        doThrow(new InternalException("dispatch failed", new DataIntegrityViolationException("insert failed", driver)))
                .when(dispatcher)
                .run();

        jobs.dispatch();

        assertThat(output.getOut())
                .contains("notifications dispatch failed, the next run will retry")
                .contains("SQLState 23505")
                .doesNotContain("anna@example.com");
    }
}
