package gag.nails.identity;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.identity.model.Authenticated;
import java.util.Optional;

/**
 * Сессии в PostgreSQL. Токен — 32 случайных байта в base64url; в базе только его SHA-256. Срок без
 * активности — 14 дней у аккаунта с доступом к админке, иначе 30; срок от входа — 90 дней. Права в
 * сессии не хранятся: {@code Actor} собирается из базы на каждый запрос, изменения прав действуют со
 * следующего запроса.
 */
public interface Sessions {

    /**
     * Вошедший по токену: аккаунт, карточка клиента, итоговые уровни по разделам. Просроченная сессия
     * удаляется, заблокированная или обезличенная карточка закрывает все сессии аккаунта — в обоих
     * случаях пусто. {@code last_used_at} обновляется не чаще раза в 5 минут.
     */
    Optional<Authenticated> authenticate(String token);

    /** Выход; неизвестный токен — тихо. */
    void close(String token);

    /**
     * «Выйти на всех устройствах»: все сессии аккаунта {@code actor}.
     *
     * @throws AccessDeniedException у актора нет аккаунта
     */
    void closeAll(Actor actor);
}
