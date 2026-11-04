package gag.nails.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import org.springframework.core.io.ClassPathResource;

/**
 * Операторы из файлов миграций — чтобы тест выполнил ровно тот текст, который применяет Liquibase. Строку «как до
 * миграции» в общей базе тестов не завести: миграции уже применены. Поэтому тест готовит такую строку сам и выполняет
 * оператор из набора изменений, прочитанный из файла.
 */
final class MigrationStatements {

    private MigrationStatements() {}

    /**
     * Единственный оператор {@code UPDATE booking.booking} набора изменений {@code changeset} (вида {@code
     * nails-gag:booking-0005-…}) из файла миграции на classpath: строки набора без комментариев, разбитые по {@code ;}.
     */
    static String bookingUpdate(String migration, String changeset) throws IOException {
        var lines = new ClassPathResource(migration)
                .getContentAsString(StandardCharsets.UTF_8)
                .lines()
                .toList();
        var statements = new ArrayList<String>();
        var inside = false;
        for (var line : lines) {
            if (line.startsWith("--changeset ")) {
                inside = line.equals("--changeset " + changeset);
            } else if (inside && !line.strip().startsWith("--")) {
                statements.add(line);
            }
        }
        var updates = Arrays.stream(String.join("\n", statements).split(";"))
                .map(String::strip)
                .filter(statement -> statement.startsWith("UPDATE booking.booking"))
                .toList();
        assertThat(updates).as("UPDATE in changeset " + changeset).hasSize(1);
        return updates.getFirst();
    }
}
