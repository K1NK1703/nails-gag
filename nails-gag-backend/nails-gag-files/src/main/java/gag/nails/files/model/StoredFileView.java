package gag.nails.files.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Сохранённый файл.
 *
 * @param width ширина картинки после поворота по EXIF и до уменьшения
 * @param height высота картинки после поворота по EXIF и до уменьшения
 * @param createdAt время загрузки
 */
public record StoredFileView(UUID id, FilePurpose purpose, int width, int height, Instant createdAt) {}
