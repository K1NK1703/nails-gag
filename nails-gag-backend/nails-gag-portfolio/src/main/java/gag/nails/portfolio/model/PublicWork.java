package gag.nails.portfolio.model;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Опубликованная работа для лендинга: без клиента, записи и источника.
 *
 * @param fileId файл фото: из него строятся адреса вариантов картинки
 * @param alt описание фото
 * @param caption подпись; может отсутствовать
 * @param width ширина фото (до уменьшения): по ней с {@code height} лендинг держит пропорции до загрузки
 * @param height высота фото (до уменьшения)
 */
public record PublicWork(
        UUID id, UUID fileId, String alt, @Nullable String caption, int width, int height) {}
