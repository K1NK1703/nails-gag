package gag.nails.booking;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Уборка после тестов на настоящих транзакциях (класс теста ставит на себя {@code @Transactional(propagation =
 * NOT_SUPPORTED)}): удаление референсов по сроку идёт пачками в своих транзакциях и вне чужой не работает. Остальные
 * тесты модуля откатывают свои транзакции, поэтому зафиксированные записи, слоты и файлы — только от таких тестов, и
 * удаляются целиком. Карточки клиентов остаются: их никто не перечисляет.
 */
public final class CommittedData {

    private CommittedData() {}

    /** Удаляет записи с их референсами, слоты и файлы — строки и каталоги на диске. */
    public static void removeAll(JdbcClient jdbc, Path filesRoot) throws IOException {
        for (var table : new String[] {
            "booking.reference", "booking.booking_service", "booking.booking", "schedule.slot", "files.stored_file"
        }) {
            jdbc.sql("delete from " + table).update();
        }
        if (!Files.exists(filesRoot)) {
            return;
        }
        try (var paths = Files.walk(filesRoot)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(filesRoot)) {
                    Files.delete(path);
                }
            }
        }
    }

    /** Каталог вариантов файла-референса на диске. */
    public static Path referenceDirectory(Path filesRoot, UUID fileId) {
        return filesRoot.resolve("reference").resolve(fileId.toString());
    }
}
