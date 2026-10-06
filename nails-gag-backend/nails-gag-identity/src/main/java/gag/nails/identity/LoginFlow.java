package gag.nails.identity;

import gag.nails.clients.error.ConsentVersionOutdatedException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.identity.error.AccountBlockedException;
import gag.nails.identity.error.LoginFailedException;
import gag.nails.identity.error.ProviderUnavailableException;
import gag.nails.identity.error.RegistrationExpiredException;
import org.jspecify.annotations.Nullable;

/**
 * Вход через провайдера: начало, возврат с кодом, завершение регистрации, подтверждение объединения при
 * привязке. Обмен кода на профиль идёт только на сервере, {@code state} и PKCE обязательны. Каждый
 * исход пишется в журнал входов.
 *
 * <p>Кого узнаём при {@link LoginPurpose#LOGIN}, по порядку: способ входа {@code (provider, subject)};
 * подтверждённый телефон профиля среди {@code login_method.phone}; подтверждённая почта среди
 * {@code login_method.email}; телефон, затем почта среди карточек клиентов — с аккаунтом вход идёт в
 * него, без аккаунта регистрация привязывается к карточке; иначе регистрация с новой карточкой. Если
 * телефон указывает на один аккаунт, а почта на другой, выигрывает телефон; аккаунты сами не
 * объединяются. Сопоставление идёт только по доверенным данным: номер, который клиент ввёл сам, в нём
 * не участвует.
 */
public interface LoginFlow {

    /**
     * Начало входа: адрес провайдера и состояние для cookie. {@link LoginPurpose#LINK} — только для
     * вошедшего: {@code actor.accountId()} обязателен. Состояние живёт 10 минут.
     *
     * @throws ProviderUnavailableException провайдер выключен настройками или неизвестен
     * @throws AccessDeniedException привязка без аккаунта у актора
     */
    LoginStart start(Provider provider, LoginPurpose purpose, @Nullable Actor actor);

    /**
     * Возврат от провайдера. Аккаунт найден — способ входа создаётся или обновляется (телефон, почта,
     * имя, аватарка), открывается сессия, {@link SignedIn}. Аккаунта нет — {@link RegistrationRequired}
     * с билетом на 30 минут. При привязке: ничей способ входа добавляется ({@link Linked}), свой —
     * {@link Linked} без изменений, чужой — {@link LinkConfirmationRequired}.
     *
     * @throws LoginFailedException state не совпал или просрочен, провайдер отказал, код не принят
     * @throws ProviderUnavailableException провайдер недоступен
     * @throws AccountBlockedException карточка найденного аккаунта или карточки заблокирована; сессия не
     *     открывается
     */
    LoginResult complete(Provider provider, AuthorizationResponse response, LoginState state, ClientInfo client);

    /**
     * Завершение регистрации по билету из {@link RegistrationRequired}. В одной транзакции: аккаунт и
     * способ входа создаются, карточка привязывается или создаётся, записывается согласие
     * {@code PERSONAL_DATA} на указанную версию политики, применяется начальная выдача прав, открывается
     * сессия. Билет одноразовый.
     *
     * @throws RegistrationExpiredException билет просрочен или использован
     * @throws ConsentVersionOutdatedException версия политики не действующая (из {@code clients})
     */
    SignedIn completeRegistration(String ticket, CompleteRegistration command, ClientInfo client);

    /**
     * Подтверждение объединения аккаунтов при привязке чужого способа входа: карточки объединяются
     * ({@code clients.mergeOwn}), аккаунт {@code actor} остаётся, способы входа, роли и переопределения
     * другого аккаунта переходят ему, его сессии закрываются.
     *
     * @throws RegistrationExpiredException билет просрочен, использован или выдан другому аккаунту
     * @throws AccountBlockedException любая из двух карточек заблокирована
     */
    Linked confirmLink(String ticket, Actor actor, ClientInfo client);
}
