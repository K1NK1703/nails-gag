package gag.nails.clients;

import gag.nails.clients.error.ClientBlockedException;
import gag.nails.clients.error.ClientErasedException;
import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.clients.error.ConsentVersionOutdatedException;
import gag.nails.clients.error.PhoneTakenException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Команды над клиентами. Сотрудник с уровнем изменения раздела «Клиенты» действует над любым
 * клиентом, клиент — только над собой ({@link AccessDeniedException}). Телефоны нормализуются к
 * E.164: {@code 8 (921) 123-45-67} → {@code +79211234567}; неразборчивый телефон —
 * {@link ValidationException}.
 *
 * <p>Обезличенную карточку ({@link ClientView#erased()}) менять нельзя — {@link ClientErasedException};
 * исключение — {@link #applySystemPrepaymentChange}: отметки прошлых визитов ставятся и ей.
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
     * аккаунт привязывается к нему, данные карточки сохраняются; иначе, если карточка без аккаунта с
     * такой почтой (без учёта регистра) ровно одна — привязывается к ней; иначе создаётся новый
     * клиент. Обезличенные карточки не находятся: телефона и почты у них нет. Дубликат по телефону
     * разрешается сотрудником через merge. Вызывает модуль входа, поэтому {@code Actor} не нужен.
     * Телефон — только подтверждённый провайдером входа (Яндекс ID / VK ID); телефон, введённый
     * пользователем, сюда не передавать ({@code null}).
     *
     * @throws ClientBlockedException если найденная по телефону или почте карточка заблокирована
     * @throws PhoneTakenException если телефон принадлежит клиенту, у которого уже есть другой аккаунт
     */
    ClientView register(RegisterClient command);

    /**
     * Изменение раздела «Клиенты» или сам клиент.
     *
     * @throws ClientErasedException если карточка обезличена
     * @throws ClientNotFoundException если клиента нет
     */
    ClientView update(UUID clientId, UpdateClient command, Actor actor);

    /**
     * Только с изменением раздела «Клиенты»: телефон — ключ объединения карточек, клиент его сам не меняет.
     *
     * @throws PhoneTakenException если телефон уже есть у другого клиента
     * @throws ClientErasedException если карточка обезличена
     * @throws ClientNotFoundException если клиента нет
     */
    ClientView changePhone(UUID clientId, String phone, Actor actor);

    /**
     * Номер для связи, который клиент указал сам: не подтверждён, не уникален, в привязке карточек не
     * участвует. Клиент меняет свой, сотрудник с изменением «Клиентов» — любой. Пустая строка стирает.
     *
     * @throws ValidationException если указанное нельзя прочитать как телефон
     * @throws ClientErasedException если карточка обезличена
     * @throws ClientNotFoundException если клиента нет
     */
    ClientView changeContactPhone(UUID clientId, @Nullable String phone, Actor actor);

    /**
     * Номер, подтверждённый провайдером входа, записывается в карточку, если своего подтверждённого у неё
     * нет и номер не занят другой карточкой. Вызывает модуль входа. Возвращает, записан ли номер.
     *
     * @throws ValidationException если указанное нельзя прочитать как телефон
     * @throws ClientErasedException если карточка обезличена
     * @throws ClientNotFoundException если клиента нет
     */
    boolean confirmPhone(UUID clientId, String phone);

    /**
     * Только с изменением раздела «Клиенты»; пустая строка стирает заметку.
     *
     * @throws ClientErasedException если карточка обезличена
     * @throws ClientNotFoundException если клиента нет
     */
    void updateMasterNote(UUID clientId, String note, Actor actor);

    /**
     * Записывает согласие. Клиент — только своё и только {@link ConsentSource#SITE_CHECKBOX};
     * сотрудник с изменением раздела «Клиенты» — любому клиенту и любым способом. Версия
     * документа должна быть действующей.
     *
     * @throws ConsentVersionOutdatedException если версия не действующая; в параметрах — действующая
     * @throws ClientErasedException если карточка обезличена
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
     * @throws ClientErasedException если карточка обезличена
     * @throws ClientNotFoundException если клиента нет
     */
    void setPrepaymentRequired(UUID clientId, boolean required, @Nullable String comment, Actor actor);

    /**
     * Блокировка клиента сотрудником с изменением раздела «Клиенты»: записываться сам и входить он не
     * сможет, уже созданные записи остаются мастеру. Причина до 500 символов, клиенту не видна
     * ({@link ClientQueries#blockReason}); пустая — не сохраняется. Повторная блокировка меняет причину.
     * При каждом вызове внутри транзакции публикуется {@link ClientBlocked}.
     *
     * @throws ValidationException если причина длиннее 500 символов
     * @throws ClientErasedException если карточка обезличена
     * @throws ClientNotFoundException если клиента нет
     */
    ClientView block(UUID clientId, @Nullable String reason, Actor actor);

    /**
     * Снятие блокировки сотрудником с изменением раздела «Клиенты»; причина стирается. Для
     * незаблокированного — без изменений.
     *
     * @throws ClientNotFoundException если клиента нет
     */
    ClientView unblock(UUID clientId, Actor actor);

    /**
     * Обезличивание при удалении аккаунта: имя заменяется на «Удалённый клиент», остальные личные данные,
     * заметка мастера и причина блокировки стираются, аккаунт отвязывается; блокировка и источник
     * карточки остаются. Согласия и журнал признака остаются. Публикует {@link ClientErased}. Вызывает
     * модуль входа, поэтому {@code Actor} не нужен. Повторный вызов — без изменений и без события.
     *
     * @throws ClientNotFoundException если клиента нет
     */
    void erase(UUID clientId);

    /**
     * Объединяет двух клиентов — один человек, заведённый дважды. Остаётся тот, у кого есть аккаунт;
     * если аккаунт у обоих или ни у кого — {@code targetId}. К оставшемуся переходят записи журналов и
     * то из данных, чего у него не было (в том числе номер для связи), заметка мастера дописывается;
     * признак предоплаты — если был хотя бы у одного; блокировка — если был заблокирован хотя бы один
     * (время — более раннее, причина — оставшегося, иначе удаляемого). Второй удаляется. Внутри
     * транзакции публикуется {@link ClientMerged} с аккаунтами обеих карточек, по которому другие
     * модули переписывают ссылки, а модуль входа сливает аккаунты. Нужен уровень изменения раздела
     * «Клиенты».
     *
     * @throws ClientErasedException если одна из карточек обезличена
     * @throws ValidationException если это один и тот же клиент
     * @throws ClientNotFoundException если одного из клиентов нет
     */
    MergeResult merge(UUID sourceId, UUID targetId, Actor actor);

    /**
     * Клиент объединяет со своей карточкой другую свою же (вошёл вторым способом входа). Остаётся
     * карточка вошедшего, даже если аккаунты у обеих. Правила переноса — как у {@link #merge}.
     *
     * @throws ClientBlockedException если любая из двух карточек заблокирована
     * @throws ClientErasedException если любая из двух карточек обезличена
     * @throws ValidationException если {@code otherClientId} — карточка самого вошедшего
     * @throws AccessDeniedException если у актора нет карточки клиента
     * @throws ClientNotFoundException если другой карточки нет
     */
    MergeResult mergeOwn(UUID otherClientId, Actor actor);
}
