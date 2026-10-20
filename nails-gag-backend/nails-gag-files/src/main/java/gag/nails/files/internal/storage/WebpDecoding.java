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
 * Как декодер TwelveMonkeys распакует WebP — по блокам контейнера RIFF, без распаковки. От этого зависит память:
 * пиксели с потерями ({@code VP8 }) он прореживает по ходу, а без потерь ({@code VP8L}) и прозрачность к пикселям с
 * потерями ({@code ALPH}) распаковывает на полный размер. Блоки ищутся на верхнем уровне (простой формат и расширенный
 * после {@code VP8X}) и в первом кадре анимации ({@code ANMF}).
 */
enum WebpDecoding {

    /** Только {@code VP8 }: прореживается при чтении. */
    SUBSAMPLED,

    /** {@code VP8L} или {@code ALPH}: распаковывается на полный размер холста. */
    FULL;

    private static final int CHUNK_HEADER_BYTES = 8;

    /** Заголовок кадра {@code ANMF} до вложенных блоков: смещение, размер, длительность, флаги. */
    private static final int FRAME_HEADER_BYTES = 16;

    /**
     * Как будет распакован файл, сигнатура которого уже узнана как WebP.
     *
     * @throws UnsupportedFormatException блока данных картинки нет: заголовок без пикселей
     */
    static WebpDecoding of(Path file) {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return scan(channel, 12, channel.size(), false); // после RIFF, длины и WEBP
        } catch (IOException e) {
            throw new InternalException("cannot read uploaded file", e);
        }
    }

    /**
     * Идёт по блокам от {@code position} до {@code end} до первого блока данных картинки. Прозрачность ({@code ALPH})
     * стоит перед данными — и на верхнем уровне, и в кадре.
     *
     * @param inFrame внутри кадра {@code ANMF}: здесь допустимы только {@code ALPH} и данные
     */
    private static WebpDecoding scan(FileChannel channel, long position, long end, boolean inFrame) throws IOException {
        var alpha = false;
        while (position + CHUNK_HEADER_BYTES <= end) {
            var header = readHeader(channel, position);
            switch (fourCc(header)) {
                case "VP8 " -> {
                    return alpha ? FULL : SUBSAMPLED;
                }
                case "VP8L" -> {
                    return FULL;
                }
                case "ALPH" -> {
                    alpha = true;
                    position = next(position, header);
                }
                case "ANMF" -> {
                    if (inFrame) {
                        throw new UnsupportedFormatException();
                    }
                    return scan(channel, position + CHUNK_HEADER_BYTES + FRAME_HEADER_BYTES, end, true);
                }
                default -> {
                    if (inFrame) {
                        throw new UnsupportedFormatException();
                    }
                    position = next(position, header);
                }
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
