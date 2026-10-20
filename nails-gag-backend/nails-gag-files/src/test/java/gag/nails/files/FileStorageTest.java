package gag.nails.files;

import static gag.nails.files.TestImages.decode;
import static gag.nails.files.TestImages.png;
import static gag.nails.files.TestImages.pngHeaderOnly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.files.error.ProcessingBusyException;
import gag.nails.files.error.TooLargeFileException;
import gag.nails.files.error.UnsupportedFormatException;
import gag.nails.files.internal.storage.ProcessingSlots;
import gag.nails.files.model.FilePurpose;
import gag.nails.testsupport.ModuleTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@ModuleTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FileStorageTest extends FilesTestSupport {

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    ProcessingSlots slots;

    @Test
    void storeWritesVariantsAndRow() throws NoSuchAlgorithmException {
        var content = png(2400, 1600);

        var stored = store(content, FilePurpose.PORTFOLIO);

        assertThat(stored.purpose()).isEqualTo(FilePurpose.PORTFOLIO);
        assertThat(stored.width()).isEqualTo(2400);
        assertThat(stored.height()).isEqualTo(1600);
        assertThat(stored.createdAt()).isEqualTo(clock.instant());
        assertThat(storage.find(stored.id())).contains(stored);
        assertThat(filesOnDisk())
                .containsExactlyInAnyOrder(
                        directory(stored).resolve("480.jpg"),
                        directory(stored).resolve("960.jpg"),
                        directory(stored).resolve("1600.jpg"));
        var row = jdbc.sql("select sha256, size_bytes, uploaded_by, attached_at from files.stored_file where id = :id")
                .param("id", stored.id())
                .query()
                .singleRow();
        var sha256 =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        assertThat(row)
                .containsEntry("sha256", sha256)
                .containsEntry("size_bytes", (long) content.length)
                .containsEntry("uploaded_by", ACCOUNT)
                .containsEntry("attached_at", null);
    }

    @Test
    void storeRejectsMoreThanMaxBytes() {
        var oversized = new byte[(int) MAX_FILE_BYTES + 1];

        // Заявленный размер больше лимита — отказ без чтения потока.
        assertTooLarge(() -> storage.store(
                new ByteArrayInputStream(new byte[0]), MAX_FILE_BYTES + 1, FilePurpose.PORTFOLIO, UPLOADER));
        // Заявленный размер занижен — лимит срабатывает на чтении.
        assertTooLarge(() -> storage.store(new ByteArrayInputStream(oversized), 100, FilePurpose.PORTFOLIO, UPLOADER));
        // Ровно лимит по размеру проходит и дальше отклоняется уже как не картинка.
        assertThatThrownBy(() -> store(new byte[(int) MAX_FILE_BYTES], FilePurpose.PORTFOLIO))
                .isInstanceOf(UnsupportedFormatException.class);
        assertNothingStored();
    }

    @Test
    void failedProcessingLeavesNoFiles() {
        assertThatThrownBy(() -> store(pngHeaderOnly(10, 10), FilePurpose.REFERENCE))
                .isInstanceOf(UnsupportedFormatException.class);
        assertThatThrownBy(() -> store("plain text".getBytes(StandardCharsets.UTF_8), FilePurpose.REFERENCE))
                .isInstanceOf(UnsupportedFormatException.class);

        assertNothingStored();
    }

    @Test
    void rolledBackStoreLeavesNoFiles() {
        transaction.executeWithoutResult(status -> {
            var stored = store(png(640, 480), FilePurpose.REFERENCE);
            assertThat(directory(stored)).isDirectory();
            status.setRollbackOnly();
        });

        assertNothingStored();
    }

    @Test
    void storeNeedsAccount() {
        assertThatThrownBy(() -> storage.store(
                        new ByteArrayInputStream(png(64, 48)), 100, FilePurpose.PORTFOLIO, Actor.master()))
                .isInstanceOf(AccessDeniedException.class);

        assertNothingStored();
    }

    @Test
    void openReturnsOnlyKnownWidths() throws IOException {
        var stored = store(png(1200, 900), FilePurpose.PORTFOLIO);

        var variant = storage.open(stored.id(), 960).orElseThrow();

        try (var content = variant.content()) {
            var bytes = content.readAllBytes();
            assertThat(bytes).hasSize((int) variant.length());
            assertThat(variant.length()).isEqualTo(Files.size(directory(stored).resolve("960.jpg")));
            var image = decode(bytes);
            assertThat(image.getWidth()).isEqualTo(960);
            assertThat(image.getHeight()).isEqualTo(720);
        }
        assertThat(variant.contentType()).isEqualTo("image/jpeg");
        assertThat(storage.open(stored.id(), 1000)).isEmpty();
        assertThat(storage.open(stored.id(), 2000))
                .as("width of another purpose")
                .isEmpty();
        assertThat(storage.open(UUID.randomUUID(), 480)).isEmpty();
    }

    @Test
    void attachChecksPurposeAndUploader() {
        var stored = store(png(640, 480), FilePurpose.PORTFOLIO);

        assertRejected(() -> storage.attach(stored.id(), FilePurpose.REFERENCE, ACCOUNT));
        assertRejected(() -> storage.attach(stored.id(), FilePurpose.PORTFOLIO, OTHER_ACCOUNT));
        assertRejected(() -> storage.attach(UUID.randomUUID(), FilePurpose.PORTFOLIO, ACCOUNT));
        assertThat(attachedAt(stored.id())).isNull();

        storage.attach(stored.id(), FilePurpose.PORTFOLIO, ACCOUNT);

        assertThat(attachedAt(stored.id())).isEqualTo(clock.instant());
        // Привязанный файл занят: второй владелец его не получит.
        assertRejected(() -> storage.attach(stored.id(), FilePurpose.PORTFOLIO, ACCOUNT));
    }

    @Test
    void deleteRemovesFilesAfterCommit() {
        var stored = store(png(640, 480), FilePurpose.PORTFOLIO);
        var directory = directory(stored);

        transaction.executeWithoutResult(status -> {
            storage.delete(stored.id());
            status.setRollbackOnly();
        });

        assertThat(directory).isDirectory();
        assertThat(storage.find(stored.id())).isPresent();

        transaction.executeWithoutResult(status -> {
            storage.delete(stored.id());
            assertThat(storage.find(stored.id())).isEmpty();
            // До фиксации файлы на месте: откат вернул бы строку, и ей нужны файлы.
            assertThat(directory).isDirectory();
        });

        assertThat(directory).doesNotExist();
        storage.delete(stored.id());
        assertNothingStored();
    }

    @Test
    void busyWhenAllProcessingSlotsTaken() {
        var content = png(64, 48);

        var first = slots.acquire();
        var second = slots.acquire();
        try {
            assertThatThrownBy(() -> store(content, FilePurpose.PORTFOLIO)).isInstanceOf(ProcessingBusyException.class);
        } finally {
            first.close();
            second.close();
        }

        assertNothingStored();
        assertThat(store(content, FilePurpose.PORTFOLIO).width()).isEqualTo(64);
    }

    private Instant attachedAt(UUID fileId) {
        return jdbc.sql("select attached_at from files.stored_file where id = :id")
                .param("id", fileId)
                .query(OffsetDateTime.class)
                .optional()
                .map(OffsetDateTime::toInstant)
                .orElse(null);
    }

    private static void assertTooLarge(ThrowingCallable store) {
        assertThatThrownBy(store)
                .isInstanceOfSatisfying(
                        TooLargeFileException.class,
                        e -> assertThat(e.params()).containsEntry("maxBytes", MAX_FILE_BYTES));
    }

    private static void assertRejected(ThrowingCallable attach) {
        assertThatThrownBy(attach)
                .isInstanceOfSatisfying(
                        ValidationException.class, e -> assertThat(e.field()).isEqualTo("fileId"));
    }
}
