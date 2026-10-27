package gag.nails.portfolio.internal.work;

import gag.nails.portfolio.model.PublicWork;
import gag.nails.portfolio.model.WorkSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

/**
 * Работа. Размеры фото — копия из файла: файл не меняется, а с копией список работ и страница лендинга читаются
 * одним запросом, без обращения к модулю файлов за каждой работой.
 */
@Entity
@Table(schema = "portfolio", name = "work")
class WorkEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(updatable = false)
    private UUID fileId;

    @Column(updatable = false)
    private int width;

    @Column(updatable = false)
    private int height;

    private String alt;
    private @Nullable String caption;
    private int position;
    private boolean visible;

    @Enumerated(EnumType.STRING)
    private WorkSource source;

    private @Nullable UUID bookingId;
    private @Nullable UUID clientId;

    @Column(updatable = false)
    private Instant createdAt;

    protected WorkEntity() {}

    WorkEntity(UUID fileId, int width, int height, WorkDetails details, int position, Instant createdAt) {
        this.fileId = fileId;
        this.width = width;
        this.height = height;
        this.position = position;
        this.visible = true;
        this.createdAt = createdAt;
        apply(details);
    }

    /** {@code final}: вызывается из конструктора, иначе javac предупреждает о this-escape. */
    final void apply(WorkDetails details) {
        this.alt = details.alt();
        this.caption = details.caption();
        this.source = details.source();
        this.bookingId = details.bookingId();
        this.clientId = details.clientId();
    }

    void setVisible(boolean visible) {
        this.visible = visible;
    }

    void setPosition(int position) {
        this.position = position;
    }

    /** Карточку клиента объединили с другой: работа переходит к оставшейся. */
    void reassignTo(UUID clientId) {
        this.clientId = clientId;
    }

    UUID id() {
        return id;
    }

    UUID fileId() {
        return fileId;
    }

    String alt() {
        return alt;
    }

    @Nullable
    String caption() {
        return caption;
    }

    int position() {
        return position;
    }

    boolean visible() {
        return visible;
    }

    WorkSource source() {
        return source;
    }

    @Nullable
    UUID bookingId() {
        return bookingId;
    }

    @Nullable
    UUID clientId() {
        return clientId;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    Instant createdAt() {
        return createdAt;
    }

    /** Работа на лендинге; вызывать только для опубликованной. */
    PublicWork toPublic() {
        return new PublicWork(id, fileId, alt, caption, width, height);
    }
}
