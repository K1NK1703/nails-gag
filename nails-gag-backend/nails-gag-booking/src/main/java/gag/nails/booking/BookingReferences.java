package gag.nails.booking;

import gag.nails.booking.error.BookingNotFoundException;
import gag.nails.booking.error.ReferencesClosedException;
import gag.nails.booking.error.ReferencesLimitReachedException;
import gag.nails.booking.model.ReferenceView;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import java.util.List;
import java.util.UUID;

/**
 * Фото-референсы к записи: до {@value #LIMIT} на запись. Видят их те же, кто видит запись ({@link BookingQueries#get}):
 * сотрудник с чтением «Записей» и клиент записи. Сотрудник с изменением «Записей» прикладывает и убирает их всегда.
 * Клиент записи до начала визита (согласованного более раннего времени, если он согласился прийти раньше, иначе
 * начала слота) прикладывает к активной записи и убирает любой референс своей записи при любом её состоянии, в том
 * числе приложенный мастером. Перенос записи референсы не трогает: они привязаны к записи, а не к слоту.
 *
 * <p>Хранятся срок хранения ({@code nails-gag.booking.reference-retention-days}) после отмены записи или, у любой
 * другой записи, после начала визита — даже если мастер визит не отметил; затем их вместе с файлами удаляет
 * {@link BookingMaintenance#removeExpiredReferences}. Обезличивание клиента удаляет референсы всех его записей сразу,
 * и к его записям их больше не прикладывает никто.
 */
public interface BookingReferences {

    /** Сколько референсов можно приложить к одной записи. */
    int LIMIT = 3;

    /**
     * Референсы записи в порядке добавления.
     *
     * @throws BookingNotFoundException если записи нет
     * @throws AccessDeniedException если актор без чтения «Записей» смотрит чужую запись
     */
    List<ReferenceView> list(UUID bookingId, Actor actor);

    /**
     * Прикладывает загруженный актором файл к записи; файл привязывается в той же транзакции. Вызывать как внешнюю
     * транзакцию или в транзакции, не читавшей карточку клиента записи: обезличена ли она, проверяется после
     * блокировки записи, а уже загруженную карточку транзакция взяла бы из своего кэша.
     *
     * @param fileId «сирота» назначения {@code REFERENCE}, загруженный аккаунтом актора
     * @throws BookingNotFoundException если записи нет
     * @throws AccessDeniedException если актор без изменения «Записей» и не клиент записи
     * @throws ReferencesClosedException если клиент прикладывает после начала визита или не к активной записи
     * @throws ReferencesLimitReachedException если у записи уже {@value #LIMIT} референса
     * @throws ValidationException (поле {@code bookingId}) клиент записи обезличен; (поле {@code fileId}) файла нет,
     *     его загрузил другой аккаунт (или у актора нет аккаунта), у файла другое назначение, файл уже привязан
     */
    ReferenceView add(UUID bookingId, UUID fileId, Actor actor);

    /**
     * Убирает референс, кто бы его ни приложил, и удаляет его файл (файлы на диске — после фиксации). Такого
     * референса у записи нет — ничего не делает. Сотрудник убирает и у записи обезличенного клиента.
     *
     * @throws BookingNotFoundException если записи нет
     * @throws AccessDeniedException если актор без изменения «Записей» и не клиент записи
     * @throws ReferencesClosedException если клиент убирает после начала визита
     */
    void remove(UUID bookingId, UUID fileId, Actor actor);

    /**
     * Картинку файла можно отдать этому актору: файл — референс этой записи, а запись актору видна. Права — как у
     * {@link BookingQueries#get}: чужая или несуществующая запись — исключение (HTTP отвечает не-сотруднику
     * {@code 404}, как и на чтение записи), видимая — ответ о файле.
     *
     * @return {@code false}, если файл не референс этой записи
     * @throws BookingNotFoundException если записи нет
     * @throws AccessDeniedException если актор без чтения «Записей» смотрит чужую запись
     */
    boolean imageVisible(UUID bookingId, UUID fileId, Actor actor);
}
