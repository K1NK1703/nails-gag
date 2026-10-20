package gag.nails.files.internal.storage;

import gag.nails.files.model.FilePurpose;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * «Сироты», загруженные раньше {@code createdBefore}, под блокировкой строк (Hibernate берёт FOR NO KEY UPDATE) до конца
     * транзакции. Одновременная привязка либо успевает раньше — и PostgreSQL, дождавшись её, перепроверяет условие и
     * не возвращает строку, — либо ждёт уборку и не находит файла.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from StoredFileEntity f where f.attachedAt is null and f.createdAt < :createdBefore")
    List<StoredFileEntity> lockOrphansCreatedBefore(Instant createdBefore);

    /** Какие из идентификаторов есть в таблице. */
    @Query("select f.id from StoredFileEntity f where f.id in :ids")
    List<UUID> findExistingIds(Collection<UUID> ids);
}
