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
 * Как декодер TwelveMonkeys распакует WebP — по заголовкам блоков контейнера RIFF (по 8 байт), без распаковки данных.
 * От этого зависит память: пиксели с потерями ({@code VP8 }) он прореживает по ходу, а без потерь ({@code VP8L}) и
 * прозрачность ({@code ALPH}) распаковывает на полный размер холста.
 *
 * <p>Решение консервативное: какой именно блок возьмёт декодер, не угадывается. {@link #FULL} — если {@code VP8L} или
 * {@code ALPH} есть где угодно: на верхнем уровне до конца файла или в любом кадре анимации ({@code ANMF}). Файл,
 * устроенный не так, как пишут libwebp и камеры, отвергается: подсунуть декодеру не тот блок, что увидела проверка,
 * нельзя.
 */
enum WebpDecoding {

    /** Только {@code VP8 } без прозрачности: прореживается при чтении. */
    SUBSAMPLED,

    /** Где-то есть {@code VP8L} или {@code ALPH}: распаковывается на полный размер холста. */
    FULL;

    private static final int CHUNK_HEADER_BYTES = 8;

    /** Заголовок RIFF: {@code RIFF}, длина, {@code WEBP}. */
    private static final int RIFF_HEADER_BYTES = 12;

    /** Заголовок кадра {@code ANMF} до вложенных блоков: смещение, размер, длительность, флаги. */
    private static final int FRAME_HEADER_BYTES = 16;

    /** Флаг анимации в первом байте {@code VP8X}. */
    private static final int ANIMATION_FLAG = 0x02;

    /**
     * Как будет распакован файл, сигнатура которого уже узнана как WebP.
     *
     * @throws UnsupportedFormatException нет данных картинки или структура нарушена: блок вылезает за конец файла,
     *     {@code VP8X} не первым, больше одного блока данных на верхнем уровне, данные на верхнем уровне при флаге
     *     анимации, кадры без флага анимации, кадр без данных или с несколькими блоками данных
     */
    static WebpDecoding of(Path file) {
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return new Scan(channel).topLevel();
        } catch (IOException e) {
            throw new InternalException("cannot read uploaded file", e);
        }
    }

    /** Один проход по файлу. */
    private static final class Scan {

        private final FileChannel channel;
        private boolean full;

        Scan(FileChannel channel) {
            this.channel = channel;
        }

        WebpDecoding topLevel() throws IOException {
            var riffSize = Integer.toUnsignedLong(read(4, 4).getInt(0));
            var end = Math.min(channel.size(), 8 + riffSize);
            var animated = false;
            var imageChunks = 0;
            var frames = 0;
            var position = (long) RIFF_HEADER_BYTES;
            while (end - position >= CHUNK_HEADER_BYTES) {
                var chunk = chunk(position, end);
                switch (chunk.fourCc()) {
                    case "VP8X" -> {
                        if (position != RIFF_HEADER_BYTES || chunk.size() < 1) {
                            throw new UnsupportedFormatException();
                        }
                        animated = (read(chunk.payload(), 1).get(0) & ANIMATION_FLAG) != 0;
                    }
                    case "VP8 " -> imageChunks++;
                    case "VP8L" -> {
                        imageChunks++;
                        full = true;
                    }
                    case "ALPH" -> full = true;
                    case "ANMF" -> {
                        frames++;
                        frame(chunk);
                    }
                    default -> {
                        // ICCP, EXIF, XMP, ANIM и неизвестные блоки на память декодера не влияют.
                    }
                }
                position = chunk.next();
            }
            if (imageChunks > 1
                    || (animated && imageChunks > 0)
                    || (!animated && frames > 0)
                    || imageChunks + frames == 0) {
                throw new UnsupportedFormatException();
            }
            return full ? FULL : SUBSAMPLED;
        }

        /** Кадр анимации: заголовок кадра и ровно один блок данных, перед ним может быть {@code ALPH}. */
        private void frame(Chunk frame) throws IOException {
            if (frame.size() < FRAME_HEADER_BYTES) {
                throw new UnsupportedFormatException();
            }
            var end = frame.payload() + frame.size();
            var imageChunks = 0;
            var position = frame.payload() + FRAME_HEADER_BYTES;
            while (end - position >= CHUNK_HEADER_BYTES) {
                var chunk = chunk(position, end);
                switch (chunk.fourCc()) {
                    case "VP8 " -> imageChunks++;
                    case "VP8L" -> {
                        imageChunks++;
                        full = true;
                    }
                    case "ALPH" -> full = true;
                    case "ANMF", "VP8X" -> throw new UnsupportedFormatException();
                    default -> {
                        // Неизвестные блоки внутри кадра формат разрешает.
                    }
                }
                position = chunk.next();
            }
            if (imageChunks != 1) {
                throw new UnsupportedFormatException();
            }
        }

        /** Заголовок блока; данные, заявленные за пределами {@code end}, — поддельный или обрезанный файл. */
        private Chunk chunk(long position, long end) throws IOException {
            var header = read(position, CHUNK_HEADER_BYTES);
            var bytes = new byte[4];
            header.get(0, bytes);
            var size = Integer.toUnsignedLong(header.getInt(4));
            var payload = position + CHUNK_HEADER_BYTES;
            if (size > end - payload) {
                throw new UnsupportedFormatException();
            }
            return new Chunk(new String(bytes, StandardCharsets.US_ASCII), payload, size);
        }

        private ByteBuffer read(long position, int length) throws IOException {
            var buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer, position + buffer.position()) < 0) {
                    throw new UnsupportedFormatException();
                }
            }
            return buffer.flip();
        }
    }

    /**
     * Блок контейнера.
     *
     * @param payload смещение данных блока в файле
     * @param size длина данных — 32 бита без знака
     */
    private record Chunk(String fourCc, long payload, long size) {

        /** Начало следующего блока: блоки выравниваются до чётной длины. Всегда дальше текущего хотя бы на 8 байт. */
        long next() {
            return payload + size + (size & 1);
        }
    }
}
