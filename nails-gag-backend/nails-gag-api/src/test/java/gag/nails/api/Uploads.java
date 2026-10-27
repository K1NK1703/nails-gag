package gag.nails.api;

import gag.nails.api.ApiTestSupport.Session;
import gag.nails.common.actor.Actor;
import gag.nails.files.FileStorage;
import gag.nails.files.model.FilePurpose;
import gag.nails.testsupport.TestPictures;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;
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

    private static final String FILE_PART = "file";

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
        return upload(client, session, purpose, FILE_PART, content, filename);
    }

    /** То же с частью под другим именем: так форма теряет поле {@code file}. */
    public static RequestHeadersSpec<?> upload(
            RestTestClient client,
            @Nullable Session session,
            String purpose,
            String partName,
            byte[] content,
            String filename) {
        var spec = ApiTestSupport.csrf(client.post().uri("/api/files?purpose={purpose}", purpose))
                .contentType(new MediaType(MediaType.MULTIPART_FORM_DATA, Map.of("boundary", BOUNDARY)))
                .body(form(partName, content, filename));
        return session == null ? spec : ApiTestSupport.as(session, spec);
    }

    /**
     * PNG из одного заголовка заданных размеров и конца файла, без данных картинки: размеры читаются, распаковать
     * нечего. Так проверяется предел пикселей без многомегабайтного тела.
     */
    public static byte[] pngHeaderOnly(int width, int height) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        var header = ByteBuffer.allocate(13)
                .putInt(width)
                .putInt(height)
                .put((byte) 8) // бит на канал
                .put((byte) 2) // RGB
                .put((byte) 0)
                .put((byte) 0)
                .put((byte) 0)
                .array();
        writeChunk(out, "IHDR", header);
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        var typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        var crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    /** Тело {@code multipart/form-data} с одной частью. */
    private static byte[] form(String partName, byte[] content, String filename) {
        var head = ("--" + BOUNDARY + "\r\n"
                        + "Content-Disposition: form-data; name=\"" + partName + "\"; filename=\"" + filename + "\"\r\n"
                        + "Content-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        var tail = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        var body = new ByteArrayOutputStream(head.length + content.length + tail.length);
        body.writeBytes(head);
        body.writeBytes(content);
        body.writeBytes(tail);
        return body.toByteArray();
    }

    /**
     * Форма из картинки {@link #picture()} в поле {@code file} и ещё {@code extraFields} текстовых полей — так
     * проверяется предел числа частей.
     */
    public static RequestHeadersSpec<?> uploadWithExtraFields(
            RestTestClient client, Session session, String purpose, int extraFields) {
        var body = new ByteArrayOutputStream();
        body.writeBytes(form(FILE_PART, picture(), "photo.png"));
        // Последняя граница формы («--…--») заменяется обычной, за ней — текстовые поля.
        var closing = ("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        var bytes = body.toByteArray();
        body.reset();
        body.write(bytes, 0, bytes.length - closing.length);
        for (var i = 0; i < extraFields; i++) {
            body.writeBytes(("--" + BOUNDARY + "\r\n"
                            + "Content-Disposition: form-data; name=\"note" + i + "\"\r\n\r\n"
                            + "x\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
        }
        body.writeBytes(closing);
        var spec = ApiTestSupport.csrf(client.post().uri("/api/files?purpose={purpose}", purpose))
                .contentType(new MediaType(MediaType.MULTIPART_FORM_DATA, Map.of("boundary", BOUNDARY)))
                .body(body.toByteArray());
        return ApiTestSupport.as(session, spec);
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
