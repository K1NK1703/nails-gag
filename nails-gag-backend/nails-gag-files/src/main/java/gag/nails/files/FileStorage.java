package gag.nails.files;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.files.error.ProcessingBusyException;
import gag.nails.files.error.TooLargeFileException;
import gag.nails.files.error.TooLargeImageException;
import gag.nails.files.error.UnsupportedFormatException;
import gag.nails.files.model.FilePurpose;
import gag.nails.files.model.FileVariant;
import gag.nails.files.model.StoredFileView;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Хранение картинок. Права на назначение при загрузке проверяет HTTP-слой, права на чтение и привязку — модуль-владелец
 * файла (портфолио, записи): здесь только техника.
 *
 * <p>Жизнь файла: {@link #store} сохраняет его «сиротой», владелец привязывает его к себе {@link #attach} в своей
 * транзакции, {@link #delete} удаляет. Непривязанные файлы старше суток удаляет уборка ({@link FileMaintenance}).
 */
public interface FileStorage {

    /**
     * Проверяет, обрабатывает и сохраняет картинку: на диск — варианты ширин назначения в JPEG без метаданных, в базу —
     * строку. Файл — «сирота», пока владелец не вызовет {@link #attach}. Картинка обрабатывается вне транзакции, строка
     * пишется в транзакции вызывающего, если она есть; при её откате файлы с диска удаляются. Ошибка любого шага не
     * оставляет ни файлов, ни строки.
     *
     * <p>Вызывать вне транзакции: в транзакции вызывающего соединение с базой держится всё ожидание места и всю
     * обработку картинки (секунды), а пул соединений мал.
     *
     * @param content содержимое; закрывает его вызывающий
     * @param sizeBytes заявленный размер: больше лимита — отказ сразу, не читая поток; фактический размер всё равно
     *     считается при чтении
     * @param actor загрузивший; нужен аккаунт
     * @throws AccessDeniedException у актора нет аккаунта
     * @throws TooLargeFileException файл больше {@code nails-gag.files.max-file-bytes}
     * @throws UnsupportedFormatException по содержимому не JPEG, PNG или WebP, либо картинка не читается
     * @throws TooLargeImageException в картинке больше {@code nails-gag.files.max-pixels} пикселей
     * @throws ProcessingBusyException все места обработки заняты дольше {@code nails-gag.files.processing-wait}
     */
    StoredFileView store(InputStream content, long sizeBytes, FilePurpose purpose, Actor actor);

    /**
     * Владелец привязывает «сироту» к себе в своей транзакции: с этого момента уборка файл не трогает. Откат транзакции
     * владельца откатывает и привязку. Отказ ({@link ValidationException}) помечает транзакцию вызывающего на откат:
     * метод транзакционный и присоединяется к ней, поэтому продолжать в ней после отказа нельзя.
     *
     * @param purpose назначение, которое ждёт владелец
     * @param uploadedBy аккаунт, от имени которого действует владелец: привязать можно только загруженный им файл
     * @throws ValidationException (поле {@code fileId}) файла нет или его загрузил другой аккаунт, у файла другое
     *     назначение, файл уже привязан
     */
    void attach(UUID fileId, FilePurpose purpose, UUID uploadedBy);

    /** Файл, привязанный или нет. */
    Optional<StoredFileView> find(UUID fileId);

    /**
     * Файлы с таким содержимым, привязанные и нет, по времени загрузки. Для служебных задач — например, чтобы
     * повторный импорт не загружал то же фото второй раз; индекса по контрольной сумме нет, на горячем пути не звать.
     *
     * @param sha256 SHA-256 загруженного содержимого, строчный hex — как его считает {@link #store}
     */
    List<StoredFileView> findBySha256(String sha256);

    /**
     * Вариант для отдачи. Пусто, если файла нет или ширина не из {@link FilePurpose#widths()} его назначения.
     *
     * @param width номинальная ширина варианта (картинка в нём может быть уже — не шире оригинала)
     */
    Optional<FileVariant> open(UUID fileId, int width);

    /**
     * Удаляет строку сразу, а файлы на диске — после фиксации транзакции: при откате файлы остаются со строкой. Файла
     * нет — ничего не делает.
     */
    void delete(UUID fileId);
}
