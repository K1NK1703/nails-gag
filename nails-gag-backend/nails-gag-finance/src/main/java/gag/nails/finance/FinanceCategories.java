package gag.nails.finance;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.finance.command.CreateCategory;
import gag.nails.finance.command.RenameCategory;
import gag.nails.finance.error.CategoryNameTakenException;
import gag.nails.finance.error.CategoryNotFoundException;
import gag.nails.finance.model.CategoryView;
import gag.nails.finance.model.EntryKind;
import java.util.List;
import java.util.UUID;

/**
 * Категории доходов и расходов: у каждого вида свой список и свой порядок. Категория не удаляется — её скрывают:
 * скрытая не предлагается для новых записей, а старые записи её показывают. Название уникально в пределах вида без
 * учёта регистра. Чтение — уровень чтения раздела «Финансы», изменения — уровень изменения
 * ({@link AccessDeniedException}).
 */
public interface FinanceCategories {

    /**
     * Все категории вида по порядку, со скрытыми (с пометкой).
     *
     * @throws AccessDeniedException если у актора нет чтения «Финансов»
     */
    List<CategoryView> list(EntryKind kind, Actor actor);

    /**
     * Новая видимая категория в конце списка своего вида.
     *
     * @throws ValidationException (поле {@code name}) пустое название или длиннее 60 символов
     * @throws CategoryNameTakenException если в этом виде уже есть категория с таким названием без учёта регистра
     */
    CategoryView create(CreateCategory command, Actor actor);

    /**
     * Переименование; смена только регистра своего же названия допустима.
     *
     * @throws CategoryNotFoundException если категории нет
     * @throws ValidationException (поле {@code name}) как у {@link #create}
     * @throws CategoryNameTakenException если название занято другой категорией этого вида
     */
    CategoryView rename(UUID categoryId, RenameCategory command, Actor actor);

    /**
     * Скрывает категорию; уже скрытая — без изменений.
     *
     * @throws CategoryNotFoundException если категории нет
     */
    void hide(UUID categoryId, Actor actor);

    /**
     * Снова предлагает категорию для новых записей; видимая — без изменений.
     *
     * @throws CategoryNotFoundException если категории нет
     */
    void show(UUID categoryId, Actor actor);

    /**
     * Новый порядок категорий вида: список — идентификаторы всех категорий вида (и скрытых), каждой ровно один раз.
     * Неполный список (например, экран устарел: категорию добавили) не применяется.
     *
     * @throws ValidationException (поле {@code orderedIds}) список не совпадает со всеми категориями вида: повтор,
     *     пропуск, чужой вид или неизвестный идентификатор
     */
    void reorder(EntryKind kind, List<UUID> orderedIds, Actor actor);
}
