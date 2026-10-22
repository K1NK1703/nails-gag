package gag.nails.api.internal.files;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import gag.nails.api.Uploads;
import gag.nails.files.internal.storage.ProcessingSlots;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

/**
 * Все места обработки картинок заняты: загрузка не ждёт ({@code processing-wait=0s}) и отвечает
 * {@code 503 files.busy} с {@code Retry-After: 1} — фронт повторит. Место занимает сам тест через бин модуля файлов.
 */
@ApiTest
@TestPropertySource(properties = {"nails-gag.files.max-concurrent-processing=1", "nails-gag.files.processing-wait=0s"})
class FileProcessingBusyTest extends ApiTestSupport {

    @Autowired
    ProcessingSlots slots;

    @Test
    void busyProcessingIs503WithRetryAfter() {
        var session = signInClient();

        var slot = slots.acquire();
        try {
            Uploads.upload(client, session, "reference")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                    .expectHeader()
                    .valueEquals("Retry-After", "1")
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("files.busy");
        } finally {
            slot.close();
        }

        // Место освободилось — та же загрузка проходит.
        Uploads.upload(client, session, "reference").exchange().expectStatus().isCreated();
    }
}
