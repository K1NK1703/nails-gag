package gag.nails.files.internal.storage;

import com.drew.imaging.ImageProcessingException;
import com.drew.imaging.jpeg.JpegMetadataReader;
import com.drew.imaging.png.PngMetadataReader;
import com.drew.imaging.webp.WebpMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifReader;
import java.awt.geom.AffineTransform;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EXIF-ориентация: как повернуть и отразить хранимые пиксели, чтобы картинка встала так, как её снимали. Порядок
 * значений — как у тега Orientation (0x0112), 1–8.
 */
enum Orientation {
    NORMAL,
    FLIP_HORIZONTAL,
    ROTATE_180,
    FLIP_VERTICAL,
    TRANSPOSE,
    ROTATE_90,
    TRANSVERSE,
    ROTATE_270;

    private static final Logger log = LoggerFactory.getLogger(Orientation.class);

    /** Значение тега Orientation; вне 1–8 — {@link #NORMAL}. */
    static Orientation ofExif(int value) {
        return value >= 1 && value <= values().length ? values()[value - 1] : NORMAL;
    }

    /**
     * Ориентация из EXIF файла. Нет EXIF, нет тега или метаданные не читаются — {@link #NORMAL}: сломанные метаданные
     * не повод отказывать в загрузке, пиксели читает другой код.
     */
    static Orientation read(Path file, ImageFormat format) {
        try (var in = new BufferedInputStream(Files.newInputStream(file))) {
            var metadata = metadata(in, format);
            var exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exif == null || !exif.containsTag(ExifDirectoryBase.TAG_ORIENTATION)) {
                return NORMAL;
            }
            return ofExif(exif.getInt(ExifDirectoryBase.TAG_ORIENTATION));
        } catch (IOException | ImageProcessingException | MetadataException e) {
            log.debug("EXIF orientation is unreadable, assuming normal", e);
            return NORMAL;
        } catch (RuntimeException e) {
            // Разбор метаданных чужого файла может упасть на любой неожиданности внутри библиотеки.
            log.debug("EXIF parser failed, assuming normal orientation", e);
            return NORMAL;
        }
    }

    /** Ширина и высота меняются местами: поворот на 90° или отражение по диагонали. */
    boolean swapsAxes() {
        return ordinal() >= TRANSPOSE.ordinal();
    }

    /** Преобразование хранимой картинки {@code width}×{@code height} в правильно стоящую. */
    AffineTransform transform(int width, int height) {
        return switch (this) {
            case NORMAL -> new AffineTransform();
            case FLIP_HORIZONTAL -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case ROTATE_180 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case FLIP_VERTICAL -> new AffineTransform(1, 0, 0, -1, 0, height);
            case TRANSPOSE -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case ROTATE_90 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case TRANSVERSE -> new AffineTransform(0, -1, -1, 0, height, width);
            case ROTATE_270 -> new AffineTransform(0, -1, 1, 0, 0, width);
        };
    }

    private static Metadata metadata(BufferedInputStream in, ImageFormat format)
            throws IOException, ImageProcessingException {
        return switch (format) {
            // Из сегментов JPEG нужен только EXIF: остальные читатели (XMP, IPTC, ICC) не запускаются.
            case JPEG -> JpegMetadataReader.readMetadata(in, List.of(new ExifReader()));
            case PNG -> PngMetadataReader.readMetadata(in);
            case WEBP -> WebpMetadataReader.readMetadata(in);
        };
    }
}
