package gag.nails.files.internal.storage;

import gag.nails.common.error.InternalException;
import gag.nails.files.error.TooLargeImageException;
import gag.nails.files.error.UnsupportedFormatException;
import gag.nails.files.model.FilePurpose;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Превращает загруженную картинку в варианты для хранения (спецификация 3.2): узнаёт формат по содержимому, проверяет
 * число пикселей по заголовку, распаковывает с прореживанием, поворачивает по EXIF и записывает JPEG нужных ширин без
 * метаданных. Без Spring и без состояния, кроме лимита: один экземпляр обрабатывает файлы из разных потоков.
 */
final class ImageProcessor {

    /** Качество JPEG вариантов. */
    static final float JPEG_QUALITY = 0.85f;

    private static final Logger log = LoggerFactory.getLogger(ImageProcessor.class);

    static {
        // Плагины TwelveMonkeys регистрируются через ServiceLoader класс-лоадера, который первым тронул ImageIO.
        // В исполняемом jar Spring Boot это мог быть не лоадер приложения, поэтому поиск повторяется явно.
        ImageIO.scanForPlugins();
    }

    private final long maxPixels;

    /** @throws InternalException если в classpath нет читателя WebP: без него загрузка WebP падала бы на каждом файле */
    ImageProcessor(long maxPixels) {
        this.maxPixels = maxPixels;
        ImageFormat.WEBP.newReader().dispose();
    }

    /**
     * Обрабатывает картинку из файла.
     *
     * @throws UnsupportedFormatException содержимое не JPEG, PNG или WebP, либо картинка не читается
     * @throws TooLargeImageException по заголовку в картинке больше {@code maxPixels} пикселей
     */
    ProcessedImage process(Path source, FilePurpose purpose) {
        var format = ImageFormat.detect(source).orElseThrow(UnsupportedFormatException::new);
        var reader = format.newReader();
        try (var input = new FileImageInputStream(source.toFile())) {
            reader.setInput(input, true, true);
            var stored = storedSize(reader);
            if ((long) stored.width() * stored.height() > maxPixels) {
                throw new TooLargeImageException(maxPixels);
            }
            var orientation = Orientation.read(source, format);
            var upright = orientation.swapsAxes() ? new Size(stored.height(), stored.width()) : stored;
            var decoded = decode(reader, subsampling(upright.width(), purpose));
            var image = uprightOpaqueSrgb(decoded, orientation);
            return new ProcessedImage(upright.width(), upright.height(), variants(image, upright, purpose));
        } catch (IOException e) {
            throw new InternalException("cannot open uploaded file " + source, e);
        } finally {
            reader.dispose();
        }
    }

    private static Size storedSize(ImageReader reader) {
        try {
            var size = new Size(reader.getWidth(0), reader.getHeight(0));
            if (size.width() <= 0 || size.height() <= 0) {
                throw new UnsupportedFormatException();
            }
            return size;
        } catch (IOException | RuntimeException e) {
            throw unreadable(e);
        }
    }

    /**
     * Шаг прореживания при чтении: читается каждый {@code n}-й пиксель по обеим осям. Наибольший шаг, при котором
     * прореженная картинка остаётся не уже самого широкого варианта: большая картинка не распаковывается в память
     * целиком.
     */
    private static int subsampling(int uprightWidth, FilePurpose purpose) {
        var widest = Math.min(purpose.maxWidth(), uprightWidth);
        return Math.max(1, uprightWidth / widest);
    }

    private static BufferedImage decode(ImageReader reader, int subsampling) {
        try {
            var param = reader.getDefaultReadParam();
            param.setSourceSubsampling(subsampling, subsampling, 0, 0);
            return reader.read(0, param);
        } catch (IOException | RuntimeException e) {
            throw unreadable(e);
        }
    }

    /**
     * Ошибка декодера — признак битого или поддельного файла, а не сбоя сервера. Декодеры на чужих данных бросают и
     * непроверяемые исключения (выход за границы массива и т. п.), поэтому они тоже означают «файл не читается».
     */
    private static UnsupportedFormatException unreadable(Exception e) {
        if (e instanceof UnsupportedFormatException unsupported) {
            return unsupported;
        }
        log.debug("uploaded image is unreadable", e);
        return new UnsupportedFormatException();
    }

    /**
     * Картинка в sRGB без прозрачности (прозрачное — на белом фоне), повёрнутая по EXIF. Повороты на 90° и отражения
     * точны, поэтому без интерполяции.
     */
    private static BufferedImage uprightOpaqueSrgb(BufferedImage decoded, Orientation orientation) {
        var source = toSrgb(decoded);
        var width = source.getWidth();
        var height = source.getHeight();
        var result = orientation.swapsAxes()
                ? new BufferedImage(height, width, BufferedImage.TYPE_INT_RGB)
                : new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, result.getWidth(), result.getHeight());
            graphics.drawImage(source, orientation.transform(width, height), null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    /**
     * Цветовое пространство встроенного профиля (Display P3, Adobe RGB, CMYK) переводится в sRGB. Серые картинки
     * Java 2D рисует в RGB верно сама, а {@link ColorConvertOp} из линейного серого их бы осветлил.
     */
    private static BufferedImage toSrgb(BufferedImage image) {
        var space = image.getColorModel().getColorSpace();
        if (space.isCS_sRGB() || space.getType() == ColorSpace.TYPE_GRAY) {
            return image;
        }
        return new ColorConvertOp(ColorSpace.getInstance(ColorSpace.CS_sRGB), null).filter(image, null);
    }

    /**
     * Варианты от широкого к узкому: каждый уменьшается из предыдущего, так дешевле и не хуже. Высота считается по
     * пропорциям оригинала, а не прореженной картинки, у которой они чуть сбиты округлением.
     */
    private static Map<Integer, byte[]> variants(BufferedImage image, Size original, FilePurpose purpose) {
        var variants = new HashMap<Integer, byte[]>();
        var current = image;
        for (var nominal : purpose.widths().reversed()) {
            var width = Math.min(nominal, original.width());
            var height = Math.max(1, (int) Math.round((double) original.height() * width / original.width()));
            current = scale(current, width, height);
            variants.put(nominal, jpeg(current));
        }
        return variants;
    }

    /**
     * Уменьшение без ступенек: сначала вдвое за шаг (билинейная интерполяция на половине — это среднее четырёх
     * пикселей), последний шаг меньше чем вдвое — бикубической. Бикубическая сразу в несколько раз брала бы лишь 16
     * соседних пикселей и теряла остальные.
     */
    private static BufferedImage scale(BufferedImage image, int width, int height) {
        var current = image;
        while (current.getWidth() / 2 >= width && current.getHeight() / 2 >= height) {
            current = draw(
                    current,
                    current.getWidth() / 2,
                    current.getHeight() / 2,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        if (current.getWidth() != width || current.getHeight() != height) {
            current = draw(current, width, height, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        }
        return current;
    }

    private static BufferedImage draw(BufferedImage image, int width, int height, Object interpolation) {
        var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    /** JPEG без метаданных исходника: в выходе только JFIF, ни EXIF, ни геолокации, ни модели телефона. */
    private static byte[] jpeg(BufferedImage image) {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new InternalException("no ImageIO writer for jpeg");
        }
        var writer = writers.next();
        var bytes = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            var param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.setOutput(output);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new InternalException("cannot encode JPEG variant", e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private record Size(int width, int height) {}
}
