package gag.nails.files.internal.storage;

import gag.nails.files.model.FilePurpose;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

interface StoredFileRepository extends JpaRepository<StoredFileEntity, UUID> {

    /** Удалённый файл: что стереть с диска. */
    interface RemovedFile {

        UUID getId();

        FilePurpose getPurpose();
    }

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
     * Удаляет до {@code limit} самых старых «сирот», загруженных раньше {@code createdBefore}, и возвращает, что удалено.
     * {@code FOR UPDATE SKIP LOCKED}: строку, которую прямо сейчас привязывают или удаляют, уборка пропускает, а не
     * ждёт — ни ожидания, ни взаимной блокировки с транзакцией владельца. Пропущенная привязка либо зафиксируется (файл
     * больше не «сирота»), либо откатится, и файл уберёт следующий запуск.
     *
     * <p>Без {@code @Modifying}: с ним Spring Data выполнил бы запрос через {@code executeUpdate}, а тот не принимает
     * строки {@code RETURNING}; без него запрос идёт через {@code getResultList}. Поэтому же своей транзакции метод не
     * открывает (Spring Data считала бы его чтением): нужна транзакция вызывающего, файлы стираются после её фиксации.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
                    delete from files.stored_file
                    where id in (
                        select id from files.stored_file
                        where attached_at is null and created_at < :createdBefore
                        order by created_at
                        limit :limit
                        for update skip locked)
                    returning id, purpose""", nativeQuery = true)
    List<RemovedFile> deleteOrphans(Instant createdBefore, int limit);

    /**
     * Удаляет строку; возвращает 1, если удалил, и 0, если её уже нет. Условное удаление вместо {@code delete(entity)}:
     * одновременное удаление того же файла другой транзакцией (или уборкой) даёт 0, а не ошибку «строка уже удалена».
     */
    @Modifying
    @Query("delete from StoredFileEntity f where f.id = :id")
    int deleteRow(UUID id);

    /** Какие из идентификаторов есть в таблице. */
    @Query("select f.id from StoredFileEntity f where f.id in :ids")
    List<UUID> findExistingIds(Collection<UUID> ids);
}
