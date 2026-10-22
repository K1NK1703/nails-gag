package gag.nails.api.internal.files;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.api.ApiTest;
import gag.nails.api.ApiTestSupport;
import gag.nails.api.Uploads;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

/**
 * Загрузка фото (спецификация файлов и портфолио, §6.1): референс — любому вошедшему, фото портфолио — с изменением
 * раздела «Портфолио»; проверки содержимого — модуля файлов. Корзина загрузок здесь — две в час, поэтому каждый тест
 * загружает не больше двух раз от своего аккаунта.
 */
@ApiTest
@TestPropertySource(
        properties = {
            FilesApiTest.UPLOADS_PER_HOUR,
            // Отказ до чтения тела: Tomcat дочитывает непрочитанное тело, и клиент теста получает ответ, а не обрыв
            // соединения. В работе тело целиком принимает nginx.
            "server.tomcat.max-swallow-size=-1"
        })
class FilesApiTest extends ApiTestSupport {

    static final String UPLOADS_PER_HOUR = "nails-gag.api.rate-limits.uploads-per-hour=2";

    /** {@code spring.servlet.multipart.max-file-size} (15 МБ) — столько же, сколько у модуля файлов. */
    private static final long MAX_BYTES = 15L * 1024 * 1024;

    /** {@code spring.servlet.multipart.max-request-size} (16 МБ). */
    private static final long MAX_REQUEST_BYTES = 16L * 1024 * 1024;

    @Test
    void clientUploadsReference() {
        var body = json(Uploads.upload(client, signInClient(), "reference"), HttpStatus.CREATED);

        assertThat(UUID.fromString(field(body, "id").asString())).isNotNull();
        assertThat(field(body, "purpose").asString()).isEqualTo("REFERENCE");
        assertThat(field(body, "width").asInt()).isEqualTo(Uploads.WIDTH);
        assertThat(field(body, "height").asInt()).isEqualTo(Uploads.HEIGHT);
        assertThat(field(body, "createdAt").asString()).isNotEmpty();
    }

    @Test
    void masterUploadsPortfolioPhoto() {
        var body = json(Uploads.upload(client, signInMaster(), "portfolio"), HttpStatus.CREATED);

        assertThat(field(body, "purpose").asString()).isEqualTo("PORTFOLIO");
    }

    @Test
    void portfolioUploadNeedsRights() {
        expectProblem(Uploads.upload(client, signInClient(), "portfolio"), HttpStatus.NOT_FOUND, "common.not_found");
    }

    @Test
    void anonymousCannotUpload() {
        expectProblem(
                Uploads.upload(client, null, "reference", Uploads.picture(), "photo.png"),
                HttpStatus.UNAUTHORIZED,
                "common.unauthenticated");
    }

    @Test
    void tooLargeFileIs413() {
        var problem = json(
                Uploads.upload(client, signInClient(), "reference", new byte[(int) MAX_BYTES + 1], "big.jpg"),
                HttpStatus.CONTENT_TOO_LARGE);

        assertThat(field(problem, "code").asString()).isEqualTo("files.too_large_file");
        assertThat(field(field(problem, "params"), "maxBytes").asLong()).isEqualTo(MAX_BYTES);
    }

    /** Без сессии — {@code 401} до чтения тела: огромный файл анонима не разбирается и не даёт {@code 413}. */
    @Test
    void anonymousOversizedUploadIs401() {
        expectProblem(
                Uploads.upload(client, null, "reference", new byte[(int) MAX_BYTES + 1], "big.jpg"),
                HttpStatus.UNAUTHORIZED,
                "common.unauthenticated");
    }

    /** Клиенту фото портфолио не загрузить — отказ по правам до чтения тела, как несуществующий адрес. */
    @Test
    void clientPortfolioOversizedUploadIs404() {
        expectProblem(
                Uploads.upload(client, signInClient(), "portfolio", new byte[(int) MAX_BYTES + 1], "big.jpg"),
                HttpStatus.NOT_FOUND,
                "common.not_found");
    }

    /** Запрос длиннее {@code max-request-size} — {@code 413} по {@code Content-Length}, тело не читается. */
    @Test
    void requestOverMaxRequestSizeIs413() {
        var problem = json(
                Uploads.upload(client, signInClient(), "reference", new byte[(int) MAX_REQUEST_BYTES + 1], "big.jpg"),
                HttpStatus.CONTENT_TOO_LARGE);

        assertThat(field(problem, "code").asString()).isEqualTo("files.too_large_file");
        assertThat(field(field(problem, "params"), "maxBytes").asLong()).isEqualTo(MAX_BYTES);
    }

    @Test
    void unsupportedFormatIs400() {
        var text = "not a picture".getBytes(StandardCharsets.UTF_8);

        expectProblem(
                Uploads.upload(client, signInClient(), "reference", text, "photo.jpg"),
                HttpStatus.BAD_REQUEST,
                "files.unsupported_format");
    }

    /** Пикселей больше 50 млн по заголовку — отказ до распаковки, тело крошечное. */
    @Test
    void tooLargeImageIs400() {
        var problem = json(
                Uploads.upload(client, signInClient(), "reference", Uploads.pngHeaderOnly(8000, 7000), "huge.png"),
                HttpStatus.BAD_REQUEST);

        assertThat(field(problem, "code").asString()).isEqualTo("files.too_large_image");
        assertThat(field(field(problem, "params"), "maxPixels").asLong()).isEqualTo(50_000_000L);
    }

    @Test
    void missingFilePartIsBadRequest() {
        var problem = json(
                Uploads.upload(client, signInClient(), "reference", "photo", Uploads.picture(), "photo.png"),
                HttpStatus.BAD_REQUEST);

        assertThat(field(problem, "code").asString()).isEqualTo("common.validation");
        assertThat(field(field(problem, "errors").path(0), "field").asString()).isEqualTo("file");
    }

    @Test
    void purposeInJavaCaseIsBadRequest() {
        var problem = json(
                Uploads.upload(client, signInClient(), "REFERENCE", Uploads.picture(), "photo.png"),
                HttpStatus.BAD_REQUEST);

        assertThat(field(problem, "code").asString()).isEqualTo("common.validation");
        assertThat(field(field(problem, "errors").path(0), "field").asString()).isEqualTo("purpose");
    }

    /** Две загрузки в час — третья отказ, а остальные запросы того же аккаунта идут: корзина своя. */
    @Test
    void uploadsHaveOwnBucket() {
        var session = signInClient();
        Uploads.upload(client, session, "reference").exchange().expectStatus().isCreated();
        Uploads.upload(client, session, "reference").exchange().expectStatus().isCreated();

        Uploads.upload(client, session, "reference")
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader()
                .value(
                        "Retry-After",
                        retryAfter -> assertThat(Long.parseLong(retryAfter)).isPositive())
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("common.rate_limited");
        as(session, client.get().uri("/api/me")).exchange().expectStatus().isOk();
        // У другого аккаунта корзина загрузок своя.
        Uploads.upload(client, signInClient(), "reference")
                .exchange()
                .expectStatus()
                .isCreated();
    }
}
