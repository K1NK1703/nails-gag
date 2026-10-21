package gag.nails.booking.internal.reference;

import gag.nails.files.FileStorage;
import java.util.Collection;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Удаление референсов записей вместе с файлами (файлы на диске — после фиксации): для обезличивания клиента и срока
 * хранения. Только в транзакции вызывающего ({@code MANDATORY}), который уже держит строки записей под блокировкой:
 * параллельное добавление к той же записи идёт строго до или после, порядок блокировок — записи, затем файлы.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class ReferenceRemoval {

    private final ReferenceRepository references;
    private final FileStorage files;

    ReferenceRemoval(ReferenceRepository references, FileStorage files) {
        this.references = references;
        this.files = files;
    }

    /**
     * Удаляет все референсы записей; сами референсы читаются одним запросом.
     *
     * @return сколько удалено
     */
    public int removeAll(Collection<UUID> bookingIds) {
        if (bookingIds.isEmpty()) {
            return 0;
        }
        var all = references.findAllByBookingIdInOrderByBookingIdAscCreatedAtAscFileIdAsc(bookingIds);
        all.forEach(this::remove);
        return all.size();
    }

    void remove(ReferenceEntity reference) {
        references.delete(reference);
        files.delete(reference.fileId());
    }
}
