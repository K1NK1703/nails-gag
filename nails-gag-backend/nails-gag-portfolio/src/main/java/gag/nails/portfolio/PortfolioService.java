package gag.nails.portfolio;

import gag.nails.booking.BookingQueries;
import gag.nails.booking.error.BookingNotFoundException;
import gag.nails.clients.error.ClientNotFoundException;
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
 * пока команда ждала её, — {@link ClientNotFoundException}: запись уже у оставшейся карточки, команду достаточно
 * повторить.
 */
public interface PortfolioService {

    /**
     * Новая работа: видима и встаёт в конец. Файл привязывается к работе в той же транзакции.
     *
     * @throws ValidationException (поле {@code bookingId}) у {@link WorkSource#CLIENT} нет записи, у другого
     *     источника запись есть, клиент записи обезличен; (поле {@code fileId}) файла нет, у него другое назначение,
     *     его загрузил другой аккаунт или он уже занят; нарушены ограничения полей команды
     */
    WorkView add(AddWork command, Actor actor);

    /**
     * Правка описания, подписи, источника и записи; файл, порядок и видимость не меняются. Правила источника — как
     * у {@link #add}; у {@link WorkSource#CLIENT} клиент каждый раз берётся из записи.
     *
     * @throws WorkNotFoundException если работы нет
     * @throws ValidationException (поле {@code bookingId}) как у {@link #add}; нарушены ограничения полей команды
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
