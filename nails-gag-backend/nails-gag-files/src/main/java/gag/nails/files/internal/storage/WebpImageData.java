package gag.nails.files.internal.storage;

import gag.nails.common.error.InternalException;
import gag.nails.files.error.UnsupportedFormatException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Как сжаты пиксели WebP — по блокам контейнера RIFF, без распаковки. От этого зависит память: с потерями ({@code VP8
 * }) декодер TwelveMonkeys прореживает картинку по ходу, а без потерь ({@code VP8L}) распаковывает целиком. Блок данных
 * ищется на верхнем уровне (простой формат и расширенный после {@code VP8X}) и в первом кадре анимации ({@code ANMF}).
 */
enum WebpImageData {
    LOSSY,
    LOSSLESS;

    private static final int CHUNK_HEADER_BYTES = 8;

    /** Заголовок кадра {@code ANMF} до вложенных блоков: смещение, размер, длительность, флаги. */
    private static final int FRAME_HEADER_BYTES = 16;

    /**
     * Блок данных картинки файла, сигнатура которого уже узнана как WebP.
     *
     * @throws UnsupportedFormatException блока данных картинки нет: заголовок без пикселей
     */
    static WebpImageData of(Path file) {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            var end = channel.size();
            var position = 12L; // RIFF, длина, WEBP
            while (position + CHUNK_HEADER_BYTES <= end) {
                var header = readHeader(channel, position);
                var fourCc = fourCc(header);
                switch (fourCc) {
                    case "VP8 " -> {
                        return LOSSY;
                    }
                    case "VP8L" -> {
                        return LOSSLESS;
                    }
                    case "ANMF" -> {
                        return firstFrame(channel, position + CHUNK_HEADER_BYTES + FRAME_HEADER_BYTES, end);
                    }
                    default -> position = next(position, header);
                }
            }
        } catch (IOException e) {
            throw new InternalException("cannot read uploaded file", e);
        }
        throw new UnsupportedFormatException();
    }

    /** Данные первого кадра: перед ними может стоять блок прозрачности {@code ALPH}. */
    private static WebpImageData firstFrame(FileChannel channel, long position, long end) throws IOException {
        while (position + CHUNK_HEADER_BYTES <= end) {
            var header = readHeader(channel, position);
            switch (fourCc(header)) {
                case "VP8 " -> {
                    return LOSSY;
                }
                case "VP8L" -> {
                    return LOSSLESS;
                }
                case "ALPH" -> position = next(position, header);
                default -> throw new UnsupportedFormatException();
            }
        }
        throw new UnsupportedFormatException();
    }

    private static ByteBuffer readHeader(FileChannel channel, long position) throws IOException {
        var header = ByteBuffer.allocate(CHUNK_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        while (header.hasRemaining()) {
            if (channel.read(header, position + header.position()) < 0) {
                throw new UnsupportedFormatException();
            }
        }
        return header.flip();
    }

    private static String fourCc(ByteBuffer header) {
        var bytes = new byte[4];
        header.get(0, bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    /** Начало следующего блока: длина данных — 32 бита без знака, блок выравнивается до чётной длины. */
    private static long next(long position, ByteBuffer header) {
        var size = Integer.toUnsignedLong(header.getInt(4));
        return position + CHUNK_HEADER_BYTES + size + (size & 1);
    }
}
