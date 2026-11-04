package gag.nails.common.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Идентификаторы UUID версии 7 (RFC 9562, §5.7) для строк, которые модуль вставляет мимо Hibernate (например, ради
 * {@code ON CONFLICT}): той же версии, что и у сущностей ({@code @UuidGenerator(style = VERSION_7)}).
 *
 * <p>Раскладка: 48 бит — миллисекунды Unix-времени, 4 бита — версия {@code 7}, 12 бит случайные, 2 бита — вариант
 * RFC 9562 ({@code 10}), 62 бита случайные. Идентификаторы растут вместе с миллисекундой (в индексе вставки идут в
 * конец); внутри одной миллисекунды порядок случайный — RFC это допускает. Случайные биты — из {@link SecureRandom}:
 * идентификаторы видны в адресах API, и следующий не должен угадываться по предыдущему, а {@code ThreadLocalRandom}
 * предсказуем. Время — системные часы, а не {@code Clock} приложения: идентификатор не бизнес-время, и подставные
 * часы тестов не должны давать повторов.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {}

    /** Новый идентификатор с текущей миллисекундой. */
    public static UUID next() {
        return at(System.currentTimeMillis());
    }

    /** Идентификатор с заданной миллисекундой Unix-времени (младшие 48 бит). */
    static UUID at(long epochMillis) {
        var mostSignificant = (epochMillis & 0xFFFF_FFFF_FFFFL) << 16 | 0x7000L | (RANDOM.nextLong() & 0x0FFFL);
        var leastSignificant = RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL | 0x8000_0000_0000_0000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}
