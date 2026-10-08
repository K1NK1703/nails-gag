package gag.nails.identity;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.identity.command.CreateRole;
import gag.nails.identity.command.UpdateRole;
import gag.nails.identity.error.AccountNotFoundException;
import gag.nails.identity.error.LastAccessAdminException;
import gag.nails.identity.error.RoleBuiltInException;
import gag.nails.identity.error.RoleCodeTakenException;
import gag.nails.identity.error.RoleInUseException;
import gag.nails.identity.error.RoleNotFoundException;
import gag.nails.identity.model.AccessChangeView;
import gag.nails.identity.model.AccountAccessView;
import gag.nails.identity.model.LoginEventView;
import gag.nails.identity.model.RoleView;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Раздел «Доступ»: роли, их назначение, личные переопределения, журналы. Чтение требует уровня чтения
 * раздела {@link Section#ACCESS}, изменение — уровня изменения ({@link AccessDeniedException}).
 *
 * <p>Итоговый уровень раздела у аккаунта: личное переопределение, если оно есть; иначе наибольший
 * уровень среди ролей; иначе {@link AccessLevel#NONE}.
 *
 * <p>Правило последнего администратора: в системе всегда есть хотя бы один аккаунт с итоговым {@code
 * ACCESS = WRITE}, чья карточка не заблокирована и не обезличена (аккаунт без карточки считается
 * активным). Операция, после которой это перестало бы выполняться — снятие роли, переопределение,
 * изменение или удаление роли, удаление аккаунта администратора, блокировка, объединение, — отклоняется с
 * {@link LastAccessAdminException}; проверка идёт под блокировкой, чтобы два одновременных понижения не
 * обошли её. До появления первого администратора ({@code bootstrap-developers}) правило не применяется к
 * блокировке и объединению: прав они не меняют, а терять системе нечего. Каждая результативная операция
 * оставляет строку в журнале прав.
 */
public interface AccessAdministration {

    /** Все роли по коду. */
    List<RoleView> roles(Actor actor);

    /**
     * Новая роль; разделы с {@link AccessLevel#NONE} отбрасываются.
     *
     * @throws RoleCodeTakenException код занят
     * @throws ValidationException команда не прошла проверку
     */
    RoleView createRole(CreateRole command, Actor actor);

    /**
     * Название и уровни; код не меняется. Встроенные роли менять можно, но у {@code DEVELOPER} уровень
     * {@link Section#ACCESS} остаётся {@link AccessLevel#WRITE}.
     *
     * @throws RoleBuiltInException попытка понизить {@code ACCESS} у {@code DEVELOPER}
     * @throws LastAccessAdminException после изменения не осталось бы администратора
     * @throws RoleNotFoundException роли нет
     */
    RoleView updateRole(UUID roleId, UpdateRole command, Actor actor);

    /**
     * @throws RoleBuiltInException встроенную роль удалить нельзя
     * @throws RoleInUseException роль назначена хотя бы одному аккаунту
     * @throws RoleNotFoundException роли нет
     */
    void deleteRole(UUID roleId, Actor actor);

    /** Аккаунты хотя бы с одной ролью или переопределением, по времени создания. */
    List<AccountAccessView> staff(Actor actor);

    /**
     * Любой аккаунт: роли, переопределения, итог по всем разделам.
     *
     * @throws AccountNotFoundException аккаунта нет
     */
    AccountAccessView account(UUID accountId, Actor actor);

    /**
     * Повторное назначение — без изменений и без строки журнала.
     *
     * @throws AccountNotFoundException аккаунта нет
     * @throws RoleNotFoundException роли нет
     */
    AccountAccessView assignRole(UUID accountId, UUID roleId, Actor actor);

    /**
     * Снятие неназначенной роли — без изменений и без строки журнала.
     *
     * @throws LastAccessAdminException после снятия не осталось бы администратора
     * @throws AccountNotFoundException аккаунта нет
     * @throws RoleNotFoundException роли нет
     */
    AccountAccessView revokeRole(UUID accountId, UUID roleId, Actor actor);

    /**
     * Личное переопределение уровня раздела; {@code level == null} снимает его. Тот же уровень —
     * без строки журнала.
     *
     * @throws LastAccessAdminException после изменения не осталось бы администратора
     * @throws AccountNotFoundException аккаунта нет
     */
    AccountAccessView setOverride(UUID accountId, Section section, @Nullable AccessLevel level, Actor actor);

    /**
     * Журнал прав, новые первыми.
     *
     * @param limit 1..500
     * @throws ValidationException {@code limit} вне диапазона
     */
    List<AccessChangeView> accessJournal(int limit, Actor actor);

    /**
     * Журнал входов, новые первыми.
     *
     * @param limit 1..500
     * @throws ValidationException {@code limit} вне диапазона
     */
    List<LoginEventView> loginJournal(int limit, Actor actor);
}
