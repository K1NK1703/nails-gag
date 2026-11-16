package gag.nails.notifications.internal.maintenance;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gag.nails.common.error.InternalException;
import gag.nails.notifications.NotificationMaintenance;
import gag.nails.notifications.internal.dispatch.Dispatcher;
import org.junit.jupiter.api.Test;

/**
 * Сбой одной задачи пишется в лог и не выходит наружу: планировщик запустит её снова, старт приложения не прерывается,
 * а сбой уборки не мешает проверке реестра. Что задачи срабатывают на старте — {@code NotificationJobsStartupTest}.
 */
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
}
