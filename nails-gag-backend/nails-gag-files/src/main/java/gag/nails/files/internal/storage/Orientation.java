package gag.nails.files.internal.storage;

import com.drew.imaging.ImageProcessingException;
import com.drew.imaging.jpeg.JpegSegmentReader;
import com.drew.imaging.jpeg.JpegSegmentType;
import com.drew.imaging.png.PngChunkReader;
import com.drew.imaging.png.PngChunkType;
import com.drew.imaging.riff.RiffHandler;
import com.drew.imaging.riff.RiffReader;
import com.drew.lang.ByteArrayReader;
import com.drew.lang.StreamReader;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
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
     * Ориентация из EXIF файла. Читается только блок EXIF: в JPEG — сегмент APP1, в PNG — блок {@code eXIf}, в WebP
     * — блок {@code EXIF}; остальные блоки пропускаются не читая, сжатые текстовые блоки PNG ({@code zTXt},
     * {@code iTXt}) не распаковываются — иначе файл в мегабайт мог бы развернуться в гигабайты. Нет EXIF, нет тега или
     * метаданные не читаются — {@link #NORMAL}: сломанные метаданные не повод отказывать в загрузке, пиксели читает
     * другой код.
     */
    static Orientation read(Path file, ImageFormat format) {
        try (var in = new BufferedInputStream(Files.newInputStream(file))) {
            var metadata = new Metadata();
            for (var exif : exifBlocks(new StreamReader(in), format)) {
                new ExifReader().extract(new ByteArrayReader(exif), metadata);
            }
            var directory = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (directory == null || !directory.containsTag(ExifDirectoryBase.TAG_ORIENTATION)) {
                return NORMAL;
            }
            return ofExif(directory.getInt(ExifDirectoryBase.TAG_ORIENTATION));
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

    /** Содержимое блоков EXIF в виде TIFF, без преамбулы {@code Exif\0\0}. */
    private static List<byte[]> exifBlocks(StreamReader reader, ImageFormat format)
            throws IOException, ImageProcessingException {
        var blocks = new ArrayList<byte[]>();
        switch (format) {
            case JPEG -> {
                // Сегменты читаются до начала сжатых данных; в память попадают только APP1 (не больше 64 КБ каждый).
                var segments = JpegSegmentReader.readSegments(reader, List.of(JpegSegmentType.APP1));
                for (var segment : segments.getSegments(JpegSegmentType.APP1)) {
                    if (ExifReader.startsWithJpegExifPreamble(segment)) {
                        blocks.add(withoutPreamble(segment));
                    }
                }
            }
            case PNG -> {
                for (var chunk : new PngChunkReader().extract(reader, Set.of(PngChunkType.eXIf))) {
                    blocks.add(chunk.getBytes());
                }
            }
            case WEBP -> new RiffReader().processRiff(reader, new ExifChunks(blocks));
        }
        return blocks;
    }

    /** Некоторые программы кладут в блок EXIF вместе с преамблой сегмента JPEG. */
    private static byte[] withoutPreamble(byte[] block) {
        return ExifReader.startsWithJpegExifPreamble(block)
                ? Arrays.copyOfRange(block, ExifReader.JPEG_SEGMENT_PREAMBLE.length(), block.length)
                : block;
    }

    /** Из блоков RIFF контейнера WebP берёт только {@code EXIF}, остальные пропускаются не читая. */
    private record ExifChunks(List<byte[]> blocks) implements RiffHandler {

        private static final String EXIF = "EXIF";

        @Override
        public boolean shouldAcceptRiffIdentifier(String identifier) {
            return "WEBP".equals(identifier);
        }

        @Override
        public boolean shouldAcceptChunk(String fourCc) {
            return EXIF.equals(fourCc);
        }

        @Override
        public boolean shouldAcceptList(String fourCc) {
            return false;
        }

        @Override
        public void processChunk(String fourCc, byte[] payload) {
            if (EXIF.equals(fourCc)) {
                blocks.add(withoutPreamble(payload));
            }
        }

        @Override
        public void addError(String message) {
            log.debug("WebP container is malformed: {}", message);
        }
    }
}
