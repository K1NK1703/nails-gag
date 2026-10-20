package gag.nails.files.internal.storage;

import static gag.nails.files.TestImages.APP1;
import static gag.nails.files.TestImages.EXIF_ORIENTATION_6;
import static gag.nails.files.TestImages.WEBP;
import static gag.nails.files.TestImages.WEBP_EXIF_ORIENTATION_6;
import static gag.nails.files.TestImages.containsAscii;
import static gag.nails.files.TestImages.decode;
import static gag.nails.files.TestImages.jpegMarkers;
import static gag.nails.files.TestImages.png;
import static gag.nails.files.TestImages.pngHeaderOnly;
import static gag.nails.files.TestImages.pngWithCompressedText;
import static gag.nails.files.TestImages.pngWithExifOrientation;
import static gag.nails.files.TestImages.resource;
import static gag.nails.files.TestImages.transparentPng;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.files.error.TooLargeImageException;
import gag.nails.files.error.UnsupportedFormatException;
import gag.nails.files.model.FilePurpose;
import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageProcessorTest {

    private static final long MAX_PIXELS = 50_000_000;

    private final ImageProcessor processor = new ImageProcessor(MAX_PIXELS);

    @TempDir
    Path dir;

    @Test
    void detectsFormatByContentNotName() throws IOException {
        var pngNamedJpg = write("photo.jpg", png(640, 480));
        var textNamedPng = write("photo.png", "not an image at all".getBytes(StandardCharsets.UTF_8));

        var processed = processor.process(pngNamedJpg, FilePurpose.PORTFOLIO);

        assertThat(processed.width()).isEqualTo(640);
        assertThat(processed.height()).isEqualTo(480);
        assertThatThrownBy(() -> processor.process(textNamedPng, FilePurpose.PORTFOLIO))
                .isInstanceOf(UnsupportedFormatException.class);
    }

    @Test
    void rejectsMoreThanFiftyMegapixelsBeforeDecoding() throws IOException {
        // В файле только заголовок: дошло бы до распаковки — была бы ошибка формата, а не размера.
        var tooLarge = write("large.png", pngHeaderOnly(8000, 7000));
        var atLimit = write("limit.png", pngHeaderOnly(10_000, 5_000));

        assertThatThrownBy(() -> processor.process(tooLarge, FilePurpose.PORTFOLIO))
                .isInstanceOfSatisfying(
                        TooLargeImageException.class,
                        e -> assertThat(e.params()).containsEntry("maxPixels", MAX_PIXELS));
        assertThatThrownBy(() -> processor.process(atLimit, FilePurpose.PORTFOLIO))
                .isInstanceOf(UnsupportedFormatException.class);
    }

    @Test
    void brokenImageIsUnsupported() throws IOException {
        var noPixels = write("broken.png", pngHeaderOnly(10, 10));

        assertThatThrownBy(() -> processor.process(noPixels, FilePurpose.REFERENCE))
                .isInstanceOf(UnsupportedFormatException.class);
    }

    @Test
    void variantsHaveExpectedWidths() throws IOException {
        var source = write("wide.png", png(2400, 1600));

        var portfolio = processor.process(source, FilePurpose.PORTFOLIO);
        var reference = processor.process(source, FilePurpose.REFERENCE);

        assertThat(portfolio.width()).isEqualTo(2400);
        assertThat(portfolio.height()).isEqualTo(1600);
        assertThat(portfolio.variants()).containsOnlyKeys(480, 960, 1600);
        assertSize(portfolio.variants().get(480), 480, 320);
        assertSize(portfolio.variants().get(960), 960, 640);
        assertSize(portfolio.variants().get(1600), 1600, 1067);
        assertThat(reference.variants()).containsOnlyKeys(2000);
        assertSize(reference.variants().get(2000), 2000, 1333);
    }

    @Test
    void variantIsNotWiderThanOriginal() throws IOException {
        var source = write("small.png", png(800, 600));

        var processed = processor.process(source, FilePurpose.PORTFOLIO);

        assertThat(processed.variants()).containsOnlyKeys(480, 960, 1600);
        assertSize(processed.variants().get(480), 480, 360);
        assertSize(processed.variants().get(960), 800, 600);
        assertSize(processed.variants().get(1600), 800, 600);
    }

    @Test
    void largeImageIsSubsampledWithoutLosingVariantWidth() throws IOException {
        // 4100 / 2000 = 2: читается каждый второй пиксель, 2050 px — не уже нужных 2000.
        var source = write("huge.png", png(4100, 2050));

        var processed = processor.process(source, FilePurpose.REFERENCE);

        assertThat(processed.width()).isEqualTo(4100);
        assertThat(processed.height()).isEqualTo(2050);
        assertSize(processed.variants().get(2000), 2000, 1000);
    }

    @Test
    void exifOrientationIsAppliedAndMetadataDropped() throws IOException {
        var source = write("rotated.jpg", resource(EXIF_ORIENTATION_6));

        var processed = processor.process(source, FilePurpose.PORTFOLIO);

        // Хранится 300×200, после поворота на 90° по часовой — 200×300.
        assertThat(processed.width()).isEqualTo(200);
        assertThat(processed.height()).isEqualTo(300);
        for (var variant : processed.variants().values()) {
            assertThat(jpegMarkers(variant)).doesNotContain(APP1);
            assertThat(containsAscii(variant, "Exif")).isFalse();
            assertThat(containsAscii(variant, "nails-gag-test")).isFalse();
        }
        var image = decode(processed.variants().get(480));
        assertThat(image.getWidth()).isEqualTo(200);
        assertThat(image.getHeight()).isEqualTo(300);
        // Левая (красная) половина хранимой картинки после поворота по часовой оказывается сверху.
        var top = new Color(image.getRGB(100, 20));
        var bottom = new Color(image.getRGB(100, 280));
        assertThat(top.getRed()).isGreaterThan(180);
        assertThat(top.getBlue()).isLessThan(80);
        assertThat(bottom.getBlue()).isGreaterThan(180);
        assertThat(bottom.getRed()).isLessThan(80);
    }

    @Test
    void transparentPngGetsWhiteBackground() throws IOException {
        var source = write("transparent.png", transparentPng(100, 80));

        var processed = processor.process(source, FilePurpose.PORTFOLIO);

        var image = decode(processed.variants().get(480));
        for (var point : new int[][] {{0, 0}, {50, 40}, {99, 79}}) {
            var color = new Color(image.getRGB(point[0], point[1]));
            assertThat(color.getRed()).isGreaterThanOrEqualTo(250);
            assertThat(color.getGreen()).isGreaterThanOrEqualTo(250);
            assertThat(color.getBlue()).isGreaterThanOrEqualTo(250);
        }
    }

    @Test
    void readsWebp() throws IOException {
        var source = write("photo.webp", resource(WEBP));

        var processed = processor.process(source, FilePurpose.REFERENCE);

        assertThat(processed.width()).isEqualTo(320);
        assertThat(processed.height()).isEqualTo(240);
        assertSize(processed.variants().get(2000), 320, 240);
    }

    @Test
    void compressedTextIsNeverInflated() throws IOException {
        // Гигабайт нулей в zTXt: распаковка метаданных целиком исчерпала бы память тестового процесса.
        var bomb = pngWithCompressedText(10, 10, 1L << 30);
        assertThat(bomb.length).isLessThan(2 * 1024 * 1024);
        var source = write("bomb.png", bomb);

        var processed = processor.process(source, FilePurpose.REFERENCE);

        assertThat(processed.width()).isEqualTo(10);
        assertThat(processed.height()).isEqualTo(10);
    }

    @Test
    void exifOrientationIsReadFromPng() throws IOException {
        var source = write("rotated.png", pngWithExifOrientation(300, 200, 6));

        var processed = processor.process(source, FilePurpose.PORTFOLIO);

        assertThat(processed.width()).isEqualTo(200);
        assertThat(processed.height()).isEqualTo(300);
        assertSize(processed.variants().get(480), 200, 300);
    }

    @Test
    void exifOrientationIsReadFromWebp() throws IOException {
        var source = write("rotated.webp", resource(WEBP_EXIF_ORIENTATION_6));

        var processed = processor.process(source, FilePurpose.PORTFOLIO);

        assertThat(processed.width()).isEqualTo(200);
        assertThat(processed.height()).isEqualTo(300);
        var image = decode(processed.variants().get(480));
        var top = new Color(image.getRGB(100, 20));
        var bottom = new Color(image.getRGB(100, 280));
        assertThat(top.getRed()).isGreaterThan(180);
        assertThat(bottom.getBlue()).isGreaterThan(180);
    }

    private Path write(String name, byte[] content) throws IOException {
        return Files.write(dir.resolve(name), content);
    }

    private static void assertSize(byte[] jpeg, int width, int height) {
        assertThat(jpegMarkers(jpeg)).as("variant is JPEG").isNotEmpty();
        var image = decode(jpeg);
        assertThat(image.getWidth()).isEqualTo(width);
        assertThat(image.getHeight()).isEqualTo(height);
    }
}
