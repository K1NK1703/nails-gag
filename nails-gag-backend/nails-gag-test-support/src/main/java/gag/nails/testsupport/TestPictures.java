package gag.nails.testsupport;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;

/** Картинки для тестов модулей, которые загружают фото: рисуются в коде, файлов не нужно. */
public final class TestPictures {

    private TestPictures() {}

    /** PNG с градиентом: соседние пиксели различаются, поэтому поворот и уменьшение видны по содержимому. */
    public static byte[] png(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                image.setRGB(x, y, new Color(x * 255 / width, y * 255 / height, 128).getRGB());
            }
        }
        var out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
