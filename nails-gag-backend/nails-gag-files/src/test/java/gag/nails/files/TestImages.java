package gag.nails.files;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import javax.imageio.ImageIO;

/** Картинки для тестов: генерируются в коде, готовые файлы — только в {@code src/test/resources/images}. */
public final class TestImages {

    /** JPEG 300×200 с EXIF-ориентацией 6: после поворота 200×300, сверху красный, снизу синий. */
    public static final String EXIF_ORIENTATION_6 = "images/exif-orientation-6.jpg";

    /** WebP с потерями 320×240. */
    public static final String WEBP = "images/gradient.webp";

    /** WebP с потерями 300×200 с EXIF-ориентацией 6 (блок {@code EXIF} внутри {@code VP8X}). */
    public static final String WEBP_EXIF_ORIENTATION_6 = "images/exif-orientation-6.webp";

    /** Маркер JPEG APP1: в нём EXIF. */
    public static final int APP1 = 0xE1;

    private TestImages() {}

    /** PNG с плавным градиентом: сжимается хорошо, поэтому и большой файл получается маленьким. */
    public static byte[] png(int width, int height) {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                image.setRGB(x, y, new Color(x * 255 / width, y * 255 / height, 128).getRGB());
            }
        }
        return encode(image, "png");
    }

    /**
     * Серый PNG, собранный без {@link BufferedImage}: каждая строка одного тона, поэтому даже картинка в десятки
     * мегапикселей сжимается в килобайты и не занимает память теста.
     */
    public static byte[] grayPng(int width, int height) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        var header = ByteBuffer.allocate(13)
                .putInt(width)
                .putInt(height)
                .put((byte) 8) // бит на канал
                .put((byte) 0) // оттенки серого
                .put((byte) 0)
                .put((byte) 0)
                .put((byte) 0)
                .array();
        writeChunk(out, "IHDR", header);
        var pixels = new ByteArrayOutputStream();
        var deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            var row = new byte[width + 1]; // байт фильтра (0 — без фильтра) и пиксели
            var buffer = new byte[1 << 16];
            for (var y = 0; y < height; y++) {
                Arrays.fill(row, 1, row.length, (byte) (y % 256));
                deflater.setInput(row);
                while (!deflater.needsInput()) {
                    pixels.write(buffer, 0, deflater.deflate(buffer));
                }
            }
            deflater.finish();
            while (!deflater.finished()) {
                pixels.write(buffer, 0, deflater.deflate(buffer));
            }
        } finally {
            deflater.end();
        }
        writeChunk(out, "IDAT", pixels.toByteArray());
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** PNG, все пиксели которого полностью прозрачны. */
    public static byte[] transparentPng(int width, int height) {
        return encode(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png");
    }

    /**
     * PNG из одного заголовка заданных размеров и конца файла, без данных картинки: размеры читаются, а распаковать
     * нечего.
     */
    public static byte[] pngHeaderOnly(int width, int height) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        var header = ByteBuffer.allocate(13)
                .putInt(width)
                .putInt(height)
                .put((byte) 8) // бит на канал
                .put((byte) 2) // RGB
                .put((byte) 0)
                .put((byte) 0)
                .put((byte) 0)
                .array();
        writeChunk(out, "IHDR", header);
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** PNG с EXIF в блоке {@code eXIf}: в EXIF только тег Orientation. */
    public static byte[] pngWithExifOrientation(int width, int height, int orientation) {
        return insertPngChunk(png(width, height), "eXIf", tiffWithOrientation(orientation));
    }

    /**
     * PNG с текстовым блоком {@code zTXt}, который распаковывается в {@code inflatedBytes} нулей. Нули сжимаются
     * примерно в тысячу раз: гигабайт помещается в мегабайт файла.
     */
    public static byte[] pngWithCompressedText(int width, int height, long inflatedBytes) {
        var text = new ByteArrayOutputStream();
        text.writeBytes("Comment".getBytes(StandardCharsets.US_ASCII));
        text.write(0); // конец ключевого слова
        text.write(0); // метод сжатия: deflate
        var deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            var zeros = new byte[1 << 20];
            var buffer = new byte[1 << 16];
            for (var left = inflatedBytes; left > 0; left -= zeros.length) {
                deflater.setInput(zeros, 0, (int) Math.min(zeros.length, left));
                while (!deflater.needsInput()) {
                    text.write(buffer, 0, deflater.deflate(buffer));
                }
            }
            deflater.finish();
            while (!deflater.finished()) {
                text.write(buffer, 0, deflater.deflate(buffer));
            }
        } finally {
            deflater.end();
        }
        return insertPngChunk(png(width, height), "zTXt", text.toByteArray());
    }

    /** TIFF (порядок байтов Motorola) с одним IFD из одного тега Orientation — содержимое блока EXIF. */
    public static byte[] tiffWithOrientation(int orientation) {
        return ByteBuffer.allocate(26)
                .put(new byte[] {'M', 'M', 0, 42})
                .putInt(8) // смещение первого IFD
                .putShort((short) 1) // число записей
                .putShort((short) 0x0112) // Orientation
                .putShort((short) 3) // SHORT
                .putInt(1) // одно значение
                .putShort((short) orientation)
                .putShort((short) 0) // выравнивание значения до четырёх байтов
                .putInt(0) // следующего IFD нет
                .array();
    }

    /** Вставляет блок сразу после заголовка IHDR (8 байт сигнатуры + 25 байт блока). */
    public static byte[] insertPngChunk(byte[] png, String type, byte[] data) {
        var headerEnd = 8 + 25;
        var out = new ByteArrayOutputStream();
        out.write(png, 0, headerEnd);
        writeChunk(out, type, data);
        out.write(png, headerEnd, png.length - headerEnd);
        return out.toByteArray();
    }

    public static byte[] resource(String name) {
        try (var in = TestImages.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalArgumentException("no test resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static BufferedImage decode(byte[] bytes) {
        try {
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new IllegalArgumentException("not an image");
            }
            return image;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Маркеры сегментов JPEG до начала сжатых данных (SOS), без SOI. */
    public static List<Integer> jpegMarkers(byte[] jpeg) {
        if ((jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            throw new IllegalArgumentException("not a JPEG");
        }
        var markers = new ArrayList<Integer>();
        var position = 2;
        while (position + 4 <= jpeg.length) {
            if ((jpeg[position] & 0xFF) != 0xFF) {
                throw new IllegalArgumentException("broken JPEG segment at " + position);
            }
            var marker = jpeg[position + 1] & 0xFF;
            markers.add(marker);
            if (marker == 0xDA) {
                break;
            }
            var length = ((jpeg[position + 2] & 0xFF) << 8) | (jpeg[position + 3] & 0xFF);
            position += 2 + length;
        }
        return markers;
    }

    /** Содержит ли массив байтов эту строку ASCII где угодно. */
    public static boolean containsAscii(byte[] bytes, String text) {
        var needle = text.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (var i = 0; i + needle.length <= bytes.length; i++) {
            for (var j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static byte[] encode(BufferedImage image, String format) {
        var out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalArgumentException("no writer for " + format);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        var typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        var crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
