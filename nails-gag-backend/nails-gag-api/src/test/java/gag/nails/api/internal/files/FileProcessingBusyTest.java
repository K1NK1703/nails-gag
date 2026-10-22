package gag.nails.api.internal.files;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import gag.nails.api.Uploads;
import gag.nails.files.FileStorage;
import gag.nails.files.error.ProcessingBusyException;
import gag.nails.files.model.FilePurpose;
import gag.nails.files.model.StoredFileView;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Обработка картинок занята: {@code 503 files.busy} с {@code Retry-After: 1} — фронт повторит, и повтор не упирается в
 * корзину загрузок: жетон отказа возвращается. Хранилище подменено — занятость задаётся им, а не настройками модуля.
 */
@ApiTest
@TestPropertySource(properties = "nails-gag.api.rate-limits.uploads-per-hour=1")
class FileProcessingBusyTest extends ApiTestSupport {

    @MockitoBean
    FileStorage files;

    @Test
    void busyProcessingIs503WithRetryAfterAndKeepsUploadToken() {
        var session = signInClient();
        var stored = new StoredFileView(UUID.randomUUID(), FilePurpose.REFERENCE, 64, 48, Instant.now(clock));
        when(files.store(any(), anyLong(), any(), any()))
                .thenThrow(new ProcessingBusyException())
                .thenReturn(stored);

        Uploads.upload(client, session, "reference")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader()
                .valueEquals("Retry-After", "1")
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("files.busy");

        // Корзина — одна загрузка в час, но отказ «занято» её не потратил.
        Uploads.upload(client, session, "reference").exchange().expectStatus().isCreated();
    }
}
