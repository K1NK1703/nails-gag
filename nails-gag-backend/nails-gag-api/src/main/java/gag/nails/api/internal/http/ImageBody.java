package gag.nails.api.internal.http;

import java.io.InputStream;

/**
 * Тело ответа-картинки: поток файла и его длина. Не {@code Resource} намеренно: у {@code Resource} Spring MVC
 * обрабатывает {@code Range}, а картинки отдаются только целиком (спецификация файлов и портфолио, §6.2). Пишет его
 * {@link ImageBodyConverter}, он же закрывает поток.
 *
 * @param length размер в байтах — {@code Content-Length}
 */
public record ImageBody(InputStream content, long length) {}
