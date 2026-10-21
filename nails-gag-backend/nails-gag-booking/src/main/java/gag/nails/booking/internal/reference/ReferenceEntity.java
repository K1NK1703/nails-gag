package gag.nails.booking.internal.reference;

import gag.nails.booking.model.ReferenceAuthor;
import gag.nails.booking.model.ReferenceView;
import gag.nails.files.model.StoredFileView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Фото-референс к записи. Первичный ключ таблицы — пара (запись, файл), но файл и сам уникален, поэтому
 * идентификатор сущности — он. Идентификатор задаётся, а не генерируется: {@link Persistable} говорит репозиторию,
 * что новая строка — вставка, без лишнего чтения перед ней.
 */
@Entity
@Table(schema = "booking", name = "reference")
class ReferenceEntity implements Persistable<UUID> {

    @Id
    private UUID fileId;

    @Column(updatable = false)
    private UUID bookingId;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private ReferenceAuthor addedBy;

    @Column(updatable = false)
    private Instant createdAt;

    @Transient
    private boolean persisted;

    protected ReferenceEntity() {}

    ReferenceEntity(UUID bookingId, UUID fileId, ReferenceAuthor addedBy, Instant createdAt) {
        this.bookingId = bookingId;
        this.fileId = fileId;
        this.addedBy = addedBy;
        this.createdAt = createdAt;
    }

    /**
     * До вставки, а не после: строка с заданным идентификатором вставляется лишь при сбросе сессии, а удалить только
     * что сохранённую сущность до сброса репозиторий должен уметь — новую ({@link #isNew}) он не удаляет.
     */
    @PostLoad
    @PrePersist
    void markPersisted() {
        persisted = true;
    }

    @Override
    public UUID getId() {
        return fileId;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }

    UUID fileId() {
        return fileId;
    }

    UUID bookingId() {
        return bookingId;
    }

    /** Представление с размерами файла: они хранятся у файла и не меняются. */
    ReferenceView toView(StoredFileView file) {
        return new ReferenceView(fileId, addedBy, file.width(), file.height(), createdAt);
    }
}
