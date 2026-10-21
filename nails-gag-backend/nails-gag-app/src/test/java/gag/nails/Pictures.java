package gag.nails;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;

/**
 * Картинка для загрузок в тестах приложения. В модулях для этого есть {@code TestPictures} из test-support, но app его
 * не подключает: там свой класс приложения в том же пакете.
 */
final class Pictures {

    private Pictures() {}

    /** Чёрный PNG: тестам приложения содержимое не важно, только то, что это картинка. */
    static byte[] png(int width, int height) {
        var out = new ByteArrayOutputStream();
        try {
            ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
