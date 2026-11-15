package gag.nails.identity;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Section;
import gag.nails.common.error.InternalException;
import gag.nails.identity.model.StaffRecipient;
import java.util.List;

/**
 * Сотрудники для системных операций других модулей (например, кому отправить мастерское уведомление).
 * Без {@code Actor}: вызывающий модуль сам отвечает за то, что отдаёт наружу.
 */
public interface StaffDirectory {

    /**
     * Незаблокированные аккаунты с итоговым уровнем раздела не ниже {@code level}; порядок — по id. Итог
     * считается так же, как у {@code Actor} сессии: личное переопределение, иначе наибольший уровень среди
     * ролей. Аккаунт, чья карточка клиента заблокирована или обезличена, не входит (аккаунт без карточки
     * входит).
     *
     * @param level {@link AccessLevel#READ} или {@link AccessLevel#WRITE}
     * @throws InternalException {@code level} — {@link AccessLevel#NONE}
     */
    List<StaffRecipient> recipients(Section section, AccessLevel level);
}
