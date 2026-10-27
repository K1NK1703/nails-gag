package gag.nails.booking;

import gag.nails.common.actor.Actor;
import gag.nails.files.FileStorage;
import gag.nails.files.model.FilePurpose;
import gag.nails.testsupport.TestPictures;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.UUID;

/** Загрузка фото для тестов референсов: картинка рисуется в коде, файл загружает актор со своим аккаунтом. */
public final class ReferenceUploads {

    static final int WIDTH = 64;
    static final int HEIGHT = 48;

    private ReferenceUploads() {}

    /** Клиент, вошедший со своим аккаунтом: загружать файлы может только аккаунт. */
    public static Actor signedIn(UUID clientId) {
        return Actor.of(UUID.randomUUID(), clientId, Map.of());
    }

    /** Мастер со своим аккаунтом. */
    public static Actor signedInMaster() {
        return Actor.of(UUID.randomUUID(), null, Actor.masterAccess());
    }

    /** «Сирота» назначения {@code REFERENCE}, {@value #WIDTH}×{@value #HEIGHT}. */
    public static UUID upload(FileStorage files, Actor uploader) {
        return upload(files, uploader, FilePurpose.REFERENCE);
    }

    public static UUID upload(FileStorage files, Actor uploader, FilePurpose purpose) {
        var content = TestPictures.png(WIDTH, HEIGHT);
        return files.store(new ByteArrayInputStream(content), content.length, purpose, uploader)
                .id();
    }
}
