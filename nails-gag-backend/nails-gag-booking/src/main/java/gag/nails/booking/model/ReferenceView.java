package gag.nails.booking.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Фото-референс к записи.
 *
 * @param fileId файл назначения {@code REFERENCE}; по нему отдаётся картинка
 * @param addedBy кто приложил: клиент записи или сотрудник
 * @param width ширина фото в пикселях после поворота по EXIF и до уменьшения
 * @param height высота фото в тех же единицах
 * @param createdAt когда референс приложен к записи
 */
public record ReferenceView(UUID fileId, ReferenceAuthor addedBy, int width, int height, Instant createdAt) {}
