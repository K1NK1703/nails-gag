package gag.nails.booking;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Свои данные теста на настоящих транзакциях (класс теста ставит на себя {@code @Transactional(propagation =
 * NOT_SUPPORTED)}): удаление референсов по сроку идёт пачками в своих транзакциях и внутри чужой не работает, поэтому
 * такие тесты фиксируют то, что заводят. Тест регистрирует созданные карточки клиентов, слоты и файлы, а {@link
 * #removeAll} удаляет только их: записи и референсы своих клиентов, их согласия, журнал признака предоплаты и строку
 * блокировки, свои слоты, строки своих файлов и их каталоги на диске. Чужие данные базы и корень хранилища не
 * трогаются — тест не предполагает, что в базе только его строки.
 */
public final class CommittedData {

    private final JdbcClient jdbc;
    private final Path filesRoot;
    private final List<UUID> clients = new ArrayList<>();
    private final List<UUID> slots = new ArrayList<>();
    private final List<UUID> files = new ArrayList<>();

    /** @param filesRoot корень хранилища ({@code nails-gag.files.root}) */
    public CommittedData(JdbcClient jdbc, Path filesRoot) {
        this.jdbc = jdbc;
        this.filesRoot = filesRoot.toAbsolutePath().normalize();
    }

    public UUID client(UUID clientId) {
        clients.add(clientId);
        return clientId;
    }

    public UUID slot(UUID slotId) {
        slots.add(slotId);
        return slotId;
    }

    public UUID file(UUID fileId) {
        files.add(fileId);
        return fileId;
    }

    /**
     * Каталог вариантов файла-референса на диске. Раскладка хранилища — {@code <root>/<назначение>/<id>} ({@code
     * StorageLayout} модуля files, спецификация §3.1).
     */
    public Path referenceDirectory(UUID fileId) {
        return filesRoot.resolve("reference").resolve(fileId.toString());
    }

    /** Удаляет зарегистрированное; повторный вызов ничего не делает. */
    public void removeAll() throws IOException {
        if (!clients.isEmpty()) {
            var bookings = "select id from booking.booking where client_id in (:ids)";
            delete("delete from booking.reference where booking_id in (" + bookings + ")", clients);
            delete("delete from booking.booking_service where booking_id in (" + bookings + ")", clients);
            delete("delete from booking.booking where client_id in (:ids)", clients);
            delete("delete from booking.client_lock where id in (:ids)", clients);
            delete("delete from clients.consent_withdrawal where client_id in (:ids)", clients);
            delete("delete from clients.consent where client_id in (:ids)", clients);
            delete("delete from clients.prepayment_flag_change where client_id in (:ids)", clients);
            delete("delete from clients.client where id in (:ids)", clients);
        }
        if (!slots.isEmpty()) {
            delete("delete from schedule.slot where id in (:ids)", slots);
        }
        if (!files.isEmpty()) {
            delete("delete from files.stored_file where id in (:ids)", files);
        }
        for (var fileId : files) {
            deleteRecursively(referenceDirectory(fileId));
        }
        clients.clear();
        slots.clear();
        files.clear();
    }

    private void delete(String sql, Collection<UUID> ids) {
        jdbc.sql(sql).param("ids", ids).update();
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
