package gag.nails.portfolio;

import gag.nails.booking.BookingQueries;
import gag.nails.booking.error.BookingNotFoundException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.portfolio.command.AddWork;
import gag.nails.portfolio.command.UpdateWork;
import gag.nails.portfolio.error.WorkNotFoundException;
import gag.nails.portfolio.model.WorkSource;
import gag.nails.portfolio.model.WorkView;
import java.util.List;
import java.util.UUID;

/**
 * Команды над работами. Нужен уровень изменения раздела «Портфолио» ({@link AccessDeniedException}).
 *
 * <p>У работы {@link WorkSource#CLIENT} клиент берётся из записи ({@link BookingQueries#get}): запись должна быть
 * видна актору, иначе — исключения модуля записей ({@link BookingNotFoundException}, {@link AccessDeniedException}).
 * Клиент записи с обезличенной карточкой — {@link ValidationException} (поле {@code bookingId}): работы такого
 * клиента удалены вместе с его карточкой и новые не заводятся. Если карточку клиента записи объединили с другой,
 * пока команда ждала её, — тоже {@link ValidationException} (поле {@code bookingId}): запись уже у оставшейся
 * карточки, команду достаточно повторить.
 *
 * <p>Команды с работой клиента вызывать как внешнюю транзакцию или в транзакции, не читавшей карточку клиента
 * записи: карточка перечитывается после ожидания её блокировки в портфолио, а уже загруженную транзакция взяла бы
 * из своего кэша.
 */
public interface PortfolioService {

    /**
     * Новая работа: видима и встаёт в конец. Файл привязывается к работе в той же транзакции.
     *
     * @throws ValidationException (поле {@code bookingId}) у {@link WorkSource#CLIENT} нет записи, у другого
     *     источника запись есть, клиент записи обезличен или объединён; (поле {@code fileId}) файла нет, у него
     *     другое назначение, его загрузил другой аккаунт или он уже занят; ({@code alt}, {@code caption}) пустое
     *     описание, текст длиннее 200 символов
     */
    WorkView add(AddWork command, Actor actor);

    /**
     * Правка описания, подписи, источника и записи; файл, порядок и видимость не меняются. Правила источника — как
     * у {@link #add}; у {@link WorkSource#CLIENT} клиент каждый раз берётся из записи.
     *
     * @throws WorkNotFoundException если работы нет
     * @throws ValidationException (поля {@code bookingId}, {@code alt}, {@code caption}) как у {@link #add}
     */
    WorkView update(UUID workId, UpdateWork command, Actor actor);

    /**
     * Мастер показывает работу; уже видимая — без изменений.
     *
     * @throws WorkNotFoundException если работы нет
     */
    void show(UUID workId, Actor actor);

    /**
     * Мастер скрывает работу; уже скрытая — без изменений.
     *
     * @throws WorkNotFoundException если работы нет
     */
    void hide(UUID workId, Actor actor);

    /**
     * Новый порядок всех работ: список — идентификаторы всех работ, каждой ровно один раз, первая — первой на
     * лендинге. Неполный список (например, экран мастера устарел: работу добавили или удалили) не применяется.
     *
     * @throws ValidationException (поле {@code orderedWorkIds}) список не совпадает со всеми работами: повтор,
     *     пропуск или неизвестный идентификатор
     */
    void reorder(List<UUID> orderedWorkIds, Actor actor);

    /**
     * Удаляет работу и её файл (файлы на диске — после фиксации).
     *
     * @throws WorkNotFoundException если работы нет
     */
    void delete(UUID workId, Actor actor);
}
