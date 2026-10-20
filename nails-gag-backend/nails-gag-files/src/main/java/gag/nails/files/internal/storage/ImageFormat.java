package gag.nails.files.internal.storage;

import gag.nails.common.error.InternalException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;

/** Принимаемые форматы. Формат определяется по сигнатуре в начале содержимого, имя файла и заявленный тип не важны. */
enum ImageFormat {

    /** {@code FF D8 FF}. */
    JPEG("jpeg"),

    /** {@code 89 50 4E 47 0D 0A 1A 0A}. */
    PNG("png"),

    /**
     * {@code RIFF}, четыре байта длины, {@code WEBP}. Без потерь ({@code VP8L}) или с прозрачностью ({@code ALPH}) —
     * не больше 16 Мп: такие декодер распаковывает на полный размер ({@link WebpDecoding}). Однотонный WebP без потерь
     * со сжатием сильнее 2048:1 TwelveMonkeys не распаковывает (защита от «бомб»), такой файл —
     * {@code files.unsupported_format}.
     */
    WEBP("webp");

    /** Сколько первых байтов нужно, чтобы узнать любой из форматов. */
    private static final int SIGNATURE_BYTES = 12;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private final String imageIoName;

    ImageFormat(String imageIoName) {
        this.imageIoName = imageIoName;
    }

    /** Формат по первым байтам файла; пусто, если это не JPEG, PNG или WebP. */
    static Optional<ImageFormat> detect(Path file) {
        byte[] head;
        try (var in = Files.newInputStream(file)) {
            head = in.readNBytes(SIGNATURE_BYTES);
        } catch (IOException e) {
            throw new InternalException("cannot read uploaded file", e);
        }
        return detect(head);
    }

    static Optional<ImageFormat> detect(byte[] head) {
        if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
            return Optional.of(JPEG);
        }
        if (head.length >= PNG_SIGNATURE.length
                && Arrays.equals(head, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length)) {
            return Optional.of(PNG);
        }
        if (head.length >= SIGNATURE_BYTES && ascii(head, 0, "RIFF") && ascii(head, 8, "WEBP")) {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    /**
     * Новый читатель формата; закрывает его ({@link ImageReader#dispose()}) вызывающий. Для JPEG первым идёт читатель
     * TwelveMonkeys: при регистрации он ставит себя перед встроенным.
     *
     * @throws InternalException если читателя нет в classpath
     */
    ImageReader newReader() {
        var readers = ImageIO.getImageReadersByFormatName(imageIoName);
        if (!readers.hasNext()) {
            throw new InternalException("no ImageIO reader for " + imageIoName);
        }
        return readers.next();
    }

    private static boolean ascii(byte[] bytes, int offset, String text) {
        for (var i = 0; i < text.length(); i++) {
            if (bytes[offset + i] != text.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
