package gag.nails.identity;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.identity.error.AccountNotFoundException;
import gag.nails.identity.error.LastAccessAdminException;
import gag.nails.identity.event.AccountDeleted;
import gag.nails.identity.model.AccountView;
import java.util.UUID;

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
     * администратора проверяется последним, уже без уходящего, и только если уходящий — администратор;
     * отказ откатывает всё удаление. Клиент без доступа к админке удаляет аккаунт всегда, даже пока в
     * системе нет ни одного администратора. В конце, в той же транзакции, публикуется {@link AccountDeleted};
     * его публикует любое удаление аккаунта, в том числе слияние аккаунтов (объединение карточек с двумя
     * аккаунтами, привязка способа входа другого аккаунта без карточки).
     *
     * @throws LastAccessAdminException уходящий — последний активный администратор
     * @throws AccessDeniedException у актора нет аккаунта
     * @throws AccountNotFoundException аккаунта уже нет
     */
    void delete(Actor actor);

    /**
     * Системная операция для модулей, которые хранят данные аккаунта: блокирует его строку ({@code FOR KEY SHARE})
     * до конца транзакции вызывающего, чтобы удаление аккаунта (оно берёт строку {@code FOR UPDATE}) не прошло
     * между проверкой и записью. Записанное до фиксации увидят синхронные слушатели {@link AccountDeleted};
     * удаление, зафиксированное раньше, даёт {@code false} — писать данные несуществующего аккаунта нельзя.
     * Изменения строки аккаунта (вход, блокировка) эта блокировка не задерживает.
     *
     * @return аккаунт есть и заблокирован; {@code false} — аккаунта нет (или он только что удалён)
     */
    boolean lockExisting(UUID accountId);
}
