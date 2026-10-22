package gag.nails.api;

import gag.nails.api.ApiTestSupport.Session;
import gag.nails.common.actor.Actor;
import gag.nails.files.FileStorage;
import gag.nails.files.model.FilePurpose;
import gag.nails.testsupport.TestPictures;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.web.servlet.client.RestTestClient.RequestHeadersSpec;

/** Загрузка фото в HTTP-тестах: картинки рисуются в коде ({@link TestPictures}), файлов не нужно. */
public final class Uploads {

    /** Размеры картинки {@link #picture()}. */
    public static final int WIDTH = 64;

    public static final int HEIGHT = 48;

    private static final String BOUNDARY = "nails-gag-test-boundary";

    private Uploads() {}

    /** PNG {@value #WIDTH}×{@value #HEIGHT}. */
    public static byte[] picture() {
        return TestPictures.png(WIDTH, HEIGHT);
    }

    /**
     * {@code POST /api/files?purpose=…} с полем {@code file}, как его пришлёт форма сайта: тело собрано целиком и
     * идёт с {@code Content-Length}, как у браузера; без {@code session} — от анонима.
     *
     * @param purpose назначение в адресе ({@code reference}, {@code portfolio})
     */
    public static RequestHeadersSpec<?> upload(
            RestTestClient client, @Nullable Session session, String purpose, byte[] content, String filename) {
        var spec = ApiTestSupport.csrf(client.post().uri("/api/files?purpose={purpose}", purpose))
                .contentType(new MediaType(MediaType.MULTIPART_FORM_DATA, Map.of("boundary", BOUNDARY)))
                .body(form(content, filename));
        return session == null ? spec : ApiTestSupport.as(session, spec);
    }

    /** Тело {@code multipart/form-data} с одной частью {@code file}. */
    private static byte[] form(byte[] content, String filename) {
        var head = ("--" + BOUNDARY + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                        + "Content-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        var tail = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        var body = new ByteArrayOutputStream(head.length + content.length + tail.length);
        body.writeBytes(head);
        body.writeBytes(content);
        body.writeBytes(tail);
        return body.toByteArray();
    }

    /** Картинка {@link #picture()} от имени вошедшего. */
    public static RequestHeadersSpec<?> upload(RestTestClient client, Session session, String purpose) {
        return upload(client, session, purpose, picture(), "photo.png");
    }

    /** «Сирота» для подготовки данных в обход HTTP: загружает аккаунт {@code uploader}. */
    public static UUID store(FileStorage files, Actor uploader, FilePurpose purpose) {
        var content = picture();
        return files.store(new ByteArrayInputStream(content), content.length, purpose, uploader)
                .id();
    }
}
