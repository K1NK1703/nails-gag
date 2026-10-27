package gag.nails.files.internal.storage;

import java.util.Map;

/**
 * Итог обработки картинки.
 *
 * @param width ширина после поворота по EXIF и до уменьшения
 * @param height высота после поворота по EXIF и до уменьшения
 * @param variants JPEG каждого варианта по его номинальной ширине (имени файла); картинка в варианте не шире
 *     оригинала
 */
record ProcessedImage(int width, int height, Map<Integer, byte[]> variants) {

    ProcessedImage {
        variants = Map.copyOf(variants);
    }
}
