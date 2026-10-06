package gag.nails.identity;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.identity.error.AccountNotFoundException;
import gag.nails.identity.error.LastAccessAdminException;

/** Свой аккаунт: профиль и удаление. Имя и номер для связи клиент меняет через {@code clients}. */
public interface Accounts {

    /**
     * Свой аккаунт: способы входа по времени привязки, итоговые уровни; имя и аватарка — способа входа,
     * использованного последним.
     *
     * @throws AccessDeniedException у актора нет аккаунта
     * @throws AccountNotFoundException аккаунта уже нет
     */
    AccountView me(Actor actor);

    /**
     * Удаление своего аккаунта в одной транзакции: карточка обезличивается ({@code clients.erase}), затем
     * удаляются способы входа, сессии, роли, переопределения и сам аккаунт. Правило последнего
     * администратора проверяется первым.
     *
     * @throws LastAccessAdminException если это последний администратор
     * @throws AccessDeniedException у актора нет аккаунта
     */
    void delete(Actor actor);
}
