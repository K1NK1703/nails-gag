package gag.nails.files.internal.storage;

import gag.nails.files.model.FilePurpose;
import gag.nails.files.model.StoredFileView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Строка файла. Идентификатор задаётся до вставки: каталог файла на диске называется им и готов раньше строки. Он
 * случайный (UUID v4), а не по времени, как у других таблиц: попадает в публичные адреса картинок, и угадать соседний
 * или время загрузки по нему нельзя.
 */
@Entity
@Table(schema = "files", name = "stored_file")
class StoredFileEntity {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private FilePurpose purpose;

    @Column(updatable = false)
    private int width;

    @Column(updatable = false)
    private int height;

    @Column(updatable = false)
    private String sha256;

    @Column(updatable = false)
    private long sizeBytes;

    @Column(updatable = false)
    private UUID uploadedBy;

    private @Nullable Instant attachedAt;

    @Column(updatable = false)
    private Instant createdAt;

    protected StoredFileEntity() {}

    StoredFileEntity(
            UUID id,
            FilePurpose purpose,
            int width,
            int height,
            String sha256,
            long sizeBytes,
            UUID uploadedBy,
            Instant createdAt) {
        this.id = id;
        this.purpose = purpose;
        this.width = width;
        this.height = height;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.uploadedBy = uploadedBy;
        this.createdAt = createdAt;
    }

    UUID id() {
        return id;
    }

    FilePurpose purpose() {
        return purpose;
    }

    UUID uploadedBy() {
        return uploadedBy;
    }

    StoredFileView toView() {
        return new StoredFileView(id, purpose, width, height, createdAt);
    }
}
