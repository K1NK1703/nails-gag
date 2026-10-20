package gag.nails.files.model;

import java.io.InputStream;

/**
 * Вариант файла для отдачи. Поток открыт на чтение файла с диска; закрывает его тот, кто отдаёт.
 *
 * @param length размер в байтах (для {@code Content-Length})
 * @param contentType тип содержимого; варианты всегда JPEG
 */
public record FileVariant(InputStream content, long length, String contentType) {}
