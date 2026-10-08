package gag.nails.identity;

import gag.nails.clients.error.ConsentVersionOutdatedException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.identity.command.CompleteRegistration;
import gag.nails.identity.error.AccountBlockedException;
import gag.nails.identity.error.LoginFailedException;
import gag.nails.identity.error.ProviderUnavailableException;
import gag.nails.identity.error.RegistrationExpiredException;
import gag.nails.identity.model.AuthorizationResponse;
import gag.nails.identity.model.ClientInfo;
import gag.nails.identity.model.LinkConfirmationRequired;
import gag.nails.identity.model.Linked;
import gag.nails.identity.model.LoginPurpose;
import gag.nails.identity.model.LoginResult;
import gag.nails.identity.model.LoginStart;
import gag.nails.identity.model.LoginState;
import gag.nails.identity.model.Provider;
import gag.nails.identity.model.RegistrationRequired;
import gag.nails.identity.model.SignedIn;
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
     * <p>Обмен кода у провайдера идёт без транзакции с базой, запись — короткими транзакциями; вызывать
     * без внешней транзакции. После фиксации сессии карточка перечитывается отдельной транзакцией: вход,
     * совпавший по времени с блокировкой карточки, закрывает сессию сразу — токен наружу не уходит.
     *
     * @throws LoginFailedException state не совпал или просрочен, провайдер отказал, код не принят
     * @throws ProviderUnavailableException провайдер недоступен
     * @throws AccountBlockedException карточка найденного аккаунта или карточки заблокирована (в том числе
     *     параллельно с входом); сессии у заблокированного не остаётся
     */
    LoginResult complete(Provider provider, AuthorizationResponse response, LoginState state, ClientInfo client);

    /**
     * Завершение регистрации по билету из {@link RegistrationRequired}. В одной транзакции: аккаунт и
     * способ входа создаются, карточка привязывается или создаётся, записывается согласие
     * {@code PERSONAL_DATA} на указанную версию политики, применяется начальная выдача прав, открывается
     * сессия. Билет одноразовый. Профиль сопоставляется заново: если за время заполнения формы появился
     * аккаунт с тем же способом входа, телефоном или почтой (параллельная регистрация, вход другим
     * способом), регистрация завершается входом в него. После фиксации карточка перечитывается
     * отдельной транзакцией, как у {@link #complete}.
     *
     * @throws RegistrationExpiredException билет просрочен или использован
     * @throws ConsentVersionOutdatedException версия политики не действующая (из {@code clients}); билет
     *     остаётся годным
     * @throws AccountBlockedException карточка, к которой привязалась бы регистрация, или карточка
     *     найденного аккаунта заблокирована; билет потреблён
     */
    SignedIn completeRegistration(String ticket, CompleteRegistration command, ClientInfo client);

    /**
     * Подтверждение объединения аккаунтов при привязке чужого способа входа: карточки объединяются
     * ({@code clients.mergeOwn}), аккаунт {@code actor} остаётся, способы входа, роли и переопределения
     * другого аккаунта переходят ему, его сессии закрываются.
     *
     * @throws RegistrationExpiredException билет просрочен, использован, выдан другому аккаунту или способ
     *     входа уже сменил владельца
     * @throws AccountBlockedException любая из двух карточек заблокирована
     * @throws LoginFailedException у вошедшего нет карточки, а у другого аккаунта есть: объединять нечего, а
     *     карточка другого осталась бы без аккаунта
     */
    Linked confirmLink(String ticket, Actor actor, ClientInfo client);
}
