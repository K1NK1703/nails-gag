package gag.nails.clients;

import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.clients.error.ConsentVersionOutdatedException;
import gag.nails.clients.error.MergeTwoAccountsException;
import gag.nails.clients.error.PhoneTakenException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Команды над клиентами. Сотрудник с уровнем изменения раздела «Клиенты» действует над любым клиентом,
 * клиент — только над собой ({@link AccessDeniedException}). Телефоны нормализуются к E.164: {@code 8 (921) 123-45-67} →
 * {@code +79211234567}; неразборчивый телефон — {@link ValidationException}.
 */
public interface ClientService {

    /**
     * Карточка клиента без аккаунта, заводит сотрудник с изменением раздела «Клиенты».
     *
     * @throws PhoneTakenException если телефон уже есть у другого клиента
     */
    ClientView create(CreateClient command, Actor actor);

    /**
     * Регистрация через вход. Повторная регистрация того же аккаунта возвращает его карточку без
     * изменений. Для нового аккаунта: если клиент с таким телефоном уже есть и у него нет аккаунта,
     * аккаунт привязывается к нему, данные карточки сохраняются; иначе создаётся новый клиент.
     * Дубликат по телефону разрешается сотрудником через merge. Вызывает модуль входа, поэтому
     * {@code Actor} не нужен. Телефон — только подтверждённый провайдером входа (Яндекс ID / VK ID);
     * телефон, введённый пользователем, сюда не передавать ({@code null}).
     *
     * @throws PhoneTakenException если телефон принадлежит клиенту, у которого уже есть другой аккаунт
     */
    ClientView register(RegisterClient command);

    /**
     * Изменение раздела «Клиенты» или сам клиент.
     *
     * @throws ClientNotFoundException если клиента нет
     */
    ClientView update(UUID clientId, UpdateClient command, Actor actor);

    /**
     * Только с изменением раздела «Клиенты»: телефон — ключ объединения карточек, клиент его сам не меняет.
     *
     * @throws PhoneTakenException если телефон уже есть у другого клиента
     * @throws ClientNotFoundException если клиента нет
     */
    ClientView changePhone(UUID clientId, String phone, Actor actor);

    /**
     * Только с изменением раздела «Клиенты»; пустая строка стирает заметку.
     *
     * @throws ClientNotFoundException если клиента нет
     */
    void updateMasterNote(UUID clientId, String note, Actor actor);

    /**
     * Записывает согласие. Клиент — только своё и только {@link ConsentSource#SITE_CHECKBOX};
     * сотрудник с изменением раздела «Клиенты» — любому клиенту и любым способом. Версия документа должна быть действующей.
     *
     * @throws ConsentVersionOutdatedException если версия не действующая; в параметрах — действующая
     * @throws ClientNotFoundException если клиента нет
     */
    void recordConsent(UUID clientId, RecordConsent command, Actor actor);

    /**
     * Изменение признака по правилу системы — вызывает модуль записей (поздняя отмена, неявка,
     * выполненный визит с предоплатой). Причины мастера ({@code MASTER_*}, {@code MERGED}) здесь
     * недопустимы. Каждое изменение попадает в журнал, даже если значение не поменялось.
     *
     * @throws ValidationException если причина зарезервирована за мастером
     * @throws ClientNotFoundException если клиента нет
     */
    void applySystemPrepaymentChange(UUID clientId, PrepaymentFlagChange change);

    /**
     * Сотрудник с изменением раздела «Клиенты» ставит или снимает признак вручную, с необязательным
     * комментарием до 500 символов. Каждое
     * изменение попадает в журнал, даже если значение не поменялось.
     *
     * @throws ValidationException если комментарий длиннее 500 символов
     * @throws ClientNotFoundException если клиента нет
     */
    void setPrepaymentRequired(UUID clientId, boolean required, @Nullable String comment, Actor actor);

    /**
     * Объединяет двух клиентов — один человек, заведённый дважды. Остаётся тот, у кого есть аккаунт
     * (если ни у кого — {@code targetId}); к нему переходят записи журналов и то из данных, чего у него
     * не было, заметка мастера дописывается; признак предоплаты — если был хотя бы у одного. Второй
     * удаляется.
     * Внутри транзакции публикуется {@link ClientMerged}, по которому другие модули переписывают ссылки.
     * Нужен уровень изменения раздела «Клиенты».
     *
     * @throws MergeTwoAccountsException если аккаунт есть у обоих
     * @throws ValidationException если это один и тот же клиент
     * @throws ClientNotFoundException если одного из клиентов нет
     */
    MergeResult merge(UUID sourceId, UUID targetId, Actor actor);
}
