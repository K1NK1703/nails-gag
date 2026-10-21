package gag.nails.booking.internal.reference;

import gag.nails.files.FileStorage;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Удаление референсов записи вместе с файлами (файлы на диске — после фиксации): для обезличивания клиента и срока
 * хранения. Только в транзакции вызывающего ({@code MANDATORY}), который уже держит строку записи под блокировкой:
 * параллельное добавление к той же записи идёт строго до или после, порядок блокировок — запись, затем файл.
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
     * Удаляет все референсы записи.
     *
     * @return сколько удалено
     */
    public int removeAll(UUID bookingId) {
        var all = references.findAllByBookingIdOrderByCreatedAtAscFileIdAsc(bookingId);
        all.forEach(this::remove);
        return all.size();
    }

    void remove(ReferenceEntity reference) {
        references.delete(reference);
        files.delete(reference.fileId());
    }
}
