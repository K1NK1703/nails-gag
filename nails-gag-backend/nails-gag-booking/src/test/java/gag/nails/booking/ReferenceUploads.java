package gag.nails.booking;

import gag.nails.common.actor.Actor;
import gag.nails.files.FileStorage;
import gag.nails.files.model.FilePurpose;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;

/** Загрузка фото для тестов референсов: картинка рисуется в коде, файл загружает актор со своим аккаунтом. */
final class ReferenceUploads {

    static final int WIDTH = 640;
    static final int HEIGHT = 480;

    private ReferenceUploads() {}

    /** Клиент, вошедший со своим аккаунтом: загружать файлы может только аккаунт. */
    static Actor signedIn(UUID clientId) {
        return Actor.of(UUID.randomUUID(), clientId, Map.of());
    }

    /** Мастер со своим аккаунтом. */
    static Actor signedInMaster() {
        return Actor.of(UUID.randomUUID(), null, Actor.masterAccess());
    }

    /** «Сирота» назначения {@code REFERENCE}, {@value #WIDTH}×{@value #HEIGHT}. */
    static UUID upload(FileStorage files, Actor uploader) {
        return upload(files, uploader, FilePurpose.REFERENCE);
    }

    static UUID upload(FileStorage files, Actor uploader, FilePurpose purpose) {
        var content = png();
        return files.store(new ByteArrayInputStream(content), content.length, purpose, uploader)
                .id();
    }

    private static byte[] png() {
        var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(200, 120, 160));
        graphics.fillRect(0, 0, WIDTH, HEIGHT);
        graphics.dispose();
        var out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
