package gag.nails.files.internal.storage;

import gag.nails.files.model.FilePurpose;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface StoredFileRepository extends JpaRepository<StoredFileEntity, UUID> {

    /**
     * Привязывает «сироту», если назначение и загрузивший совпадают; возвращает 1, если привязал, и 0, если нет.
     * Условное обновление вместо проверки в коде: из двух одновременных привязок одного файла пройдёт одна — вторая
     * дождётся блокировки строки и увидит, что файл уже привязан.
     *
     * <p>Идёт мимо контекста персистентности: загруженная раньше в этой транзакции копия строки останется с пустым
     * {@code attachedAt}. Это не видно снаружи — представление файла момента привязки не показывает, а все решения по
     * нему принимаются запросами к базе.
     */
    @Modifying
    @Query("update StoredFileEntity f set f.attachedAt = :now"
            + " where f.id = :id and f.purpose = :purpose and f.uploadedBy = :uploadedBy and f.attachedAt is null")
    int attach(UUID id, FilePurpose purpose, UUID uploadedBy, Instant now);
}
