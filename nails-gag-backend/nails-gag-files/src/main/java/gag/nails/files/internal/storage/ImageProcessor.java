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
import java.util.LinkedHashMap;
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

    /**
     * Сколько пикселей картинки распаковывается в память, не больше: 16 Мп — до 64 МБ в четырёх байтах на пиксель.
     * Больше прореживание и не нужно: самый широкий вариант (2000 px) в рамке N × 2N — 8 Мп.
     */
    static final long MAX_DECODED_PIXELS = 16_000_000;

    /**
     * Предел пикселей WebP, который декодер распаковывает на полный размер, без прореживания (без потерь или с
     * прозрачностью, {@link WebpDecoding#FULL}): тот же бюджет памяти, что и у прореженной картинки (спецификация 3.2,
     * п. 2).
     */
    static final long MAX_FULLY_DECODED_PIXELS = MAX_DECODED_PIXELS;

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
     * @throws UnsupportedFormatException содержимое не JPEG, PNG или WebP, в нём нет данных картинки, либо картинка не
     *     читается
     * @throws TooLargeImageException по заголовку в картинке больше {@code maxPixels} пикселей (у WebP без потерь или
     *     с прозрачностью — больше {@link #MAX_FULLY_DECODED_PIXELS})
     */
    ProcessedImage process(Path source, FilePurpose purpose) {
        var format = ImageFormat.detect(source).orElseThrow(UnsupportedFormatException::new);
        var pixelLimit = maxPixels;
        if (format == ImageFormat.WEBP && WebpDecoding.of(source) == WebpDecoding.FULL) {
            pixelLimit = Math.min(maxPixels, MAX_FULLY_DECODED_PIXELS);
        }
        var reader = format.newReader();
        try (var input = new FileImageInputStream(source.toFile())) {
            reader.setInput(input, true, true);
            var stored = storedSize(reader);
            if ((long) stored.width() * stored.height() > pixelLimit) {
                throw new TooLargeImageException(pixelLimit);
            }
            var orientation = Orientation.read(source, format);
            var upright = orientation.swapsAxes() ? stored.transposed() : stored;
            var decoded = decode(reader, subsampling(stored.width(), stored.height(), upright.width(), purpose));
            return new ProcessedImage(
                    upright.width(), upright.height(), variants(decoded, orientation, upright, purpose));
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
     * Шаг прореживания при чтении: читается каждый {@code n}-й пиксель по обеим осям. По ширине — наибольший шаг, при
     * котором прореженная картинка остаётся не уже двух самых широких вариантов: запас вдвое даёт качественному
     * уменьшению после прореживания убрать муар. Главнее бюджет памяти: распакованная картинка — не больше {@link
     * #MAX_DECODED_PIXELS}, поэтому узкая длинная прореживается и по высоте, даже если вариантам нужна вся ширина.
     *
     * @param uprightWidth ширина после поворота по EXIF: по ней считаются варианты
     */
    static int subsampling(int storedWidth, int storedHeight, int uprightWidth, FilePurpose purpose) {
        var byWidth = Math.max(1, uprightWidth / (2 * purpose.maxWidth()));
        var byMemory = (int) Math.ceil(Math.sqrt((double) storedWidth * storedHeight / MAX_DECODED_PIXELS));
        var step = Math.max(byWidth, byMemory);
        // Прореженная сторона округляется вверх, поэтому корень иногда даёт на пиксель больше бюджета.
        while ((long) Math.ceilDiv(storedWidth, step) * Math.ceilDiv(storedHeight, step) > MAX_DECODED_PIXELS) {
            step++;
        }
        return step;
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
     * Варианты от широкого к узкому. Самый широкий уменьшается из распакованной картинки ещё до поворота: перевод в
     * sRGB без прозрачности (прозрачное — на белом фоне) делает первая же отрисовка, а поворачивается уже маленький
     * растр. Каждый следующий вариант уменьшается из предыдущего — так дешевле и не хуже.
     *
     * <p>Ошибка Java 2D на этих шагах (битый ICC-профиль у {@link ColorConvertOp} и т. п.) означает нечитаемый файл.
     */
    private static Map<Integer, byte[]> variants(
            BufferedImage decoded, Orientation orientation, Size original, FilePurpose purpose) {
        var stored = new Size(decoded.getWidth(), decoded.getHeight());
        var available = orientation.swapsAxes() ? stored.transposed() : stored;
        var images = new LinkedHashMap<Integer, BufferedImage>();
        try {
            BufferedImage current = null;
            for (var nominal : purpose.widths().reversed()) {
                var size = variantSize(nominal, original, available);
                if (current == null) {
                    var target = orientation.swapsAxes() ? size.transposed() : size;
                    current = orient(scale(toSrgb(decoded), target.width(), target.height()), orientation);
                } else {
                    current = scale(current, size.width(), size.height());
                }
                images.put(nominal, current);
            }
        } catch (RuntimeException e) {
            throw unreadable(e);
        }
        var variants = new HashMap<Integer, byte[]>();
        images.forEach((nominal, image) -> variants.put(nominal, jpeg(image)));
        return variants;
    }

    /**
     * Размер варианта номинальной ширины {@code n}: не шире {@code n} и распакованной картинки, в рамке {@code n} ×
     * {@code 2n} — узкая длинная уменьшается по высоте. Пропорции — оригинала, а не прореженной картинки, у которой они
     * чуть сбиты округлением.
     *
     * @param available распакованная (прореженная) картинка после поворота
     */
    private static Size variantSize(int nominal, Size original, Size available) {
        var aspect = (double) original.height() / original.width();
        var width = Math.min(nominal, available.width());
        var height = (int) Math.round(width * aspect);
        var maxHeight = 2 * nominal;
        if (height > maxHeight) {
            height = maxHeight;
            width = (int) Math.round(maxHeight / aspect);
        }
        return new Size(Math.max(1, width), Math.clamp(height, 1, available.height()));
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

    /** Поворот по EXIF. Повороты на 90° и отражения точны, поэтому без интерполяции. */
    private static BufferedImage orient(BufferedImage image, Orientation orientation) {
        if (orientation == Orientation.NORMAL) {
            return image;
        }
        var width = image.getWidth();
        var height = image.getHeight();
        var result = orientation.swapsAxes()
                ? new BufferedImage(height, width, BufferedImage.TYPE_INT_RGB)
                : new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = result.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.drawImage(image, orientation.transform(width, height), null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    /**
     * Уменьшение без ступенек: сначала вдвое за шаг (билинейная интерполяция на половине — это среднее четырёх
     * пикселей), последний шаг меньше чем вдвое — бикубической. Бикубическая сразу в несколько раз брала бы лишь 16
     * соседних пикселей и теряла остальные. Результат — всегда непрозрачный RGB: картинка другого типа
     * перерисовывается, даже если размер уже нужный.
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
        if (current.getWidth() != width
                || current.getHeight() != height
                || current.getType() != BufferedImage.TYPE_INT_RGB) {
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
            // Прозрачное — на белом фоне: после первой отрисовки картинка непрозрачна.
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
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

    private record Size(int width, int height) {

        Size transposed() {
            return new Size(height, width);
        }
    }
}
