package gag.nails.finance.internal.category;

import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.InternalException;
import gag.nails.common.error.ValidationException;
import gag.nails.common.persistence.ConstraintViolations;
import gag.nails.common.validation.CommandValidator;
import gag.nails.finance.FinanceCategories;
import gag.nails.finance.command.CreateCategory;
import gag.nails.finance.command.RenameCategory;
import gag.nails.finance.error.CategoryNameTakenException;
import gag.nails.finance.error.CategoryNotFoundException;
import gag.nails.finance.model.CategoryView;
import gag.nails.finance.model.EntryKind;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Добавление, переименование и перестановка идут под блокировкой списка вида ({@link CategoryLocks#categories}):
 * проверка названия и позиция {@code max + 1} видят всё, что зафиксировано до них. Уникальный индекс
 * {@code category_kind_name_key} — последняя защита от записи в обход блокировки; его нарушение — та же
 * {@link CategoryNameTakenException}.
 */
@Service
@Transactional
class DefaultFinanceCategories implements FinanceCategories {

    /** Предел длины названия — как в {@code CHECK} колонки {@code name}. */
    private static final int MAX_NAME_LENGTH = 60;

    private static final String NAME_KEY = "category_kind_name_key";

    private final CategoryRepository categories;
    private final CategoryLocks locks;
    private final CommandValidator validator;
    private final Clock clock;

    DefaultFinanceCategories(
            CategoryRepository categories, CategoryLocks locks, CommandValidator validator, Clock clock) {
        this.categories = categories;
        this.locks = locks;
        this.validator = validator;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryView> list(EntryKind kind, Actor actor) {
        actor.requireRead(Section.FINANCE);
        requireKind(kind);
        return categories.findAllByKindInOrder(kind).stream()
                .map(CategoryEntity::toView)
                .toList();
    }

    @Override
    public CategoryView create(CreateCategory command, Actor actor) {
        actor.requireWrite(Section.FINANCE);
        validator.requireValid(command);
        var name = requireName(command.name());
        locks.categories(command.kind());
        if (categories.countByName(command.kind(), name) > 0) {
            throw new CategoryNameTakenException(name);
        }
        var position =
                categories.findMaxPosition(command.kind()).map(last -> last + 1).orElse(0);
        var category = new CategoryEntity(
                command.kind(), name, position, clock.instant().truncatedTo(ChronoUnit.MICROS));
        try {
            return categories.saveAndFlush(category).toView();
        } catch (DataIntegrityViolationException e) {
            throw nameTakenOr(e, name);
        }
    }

    @Override
    public CategoryView rename(UUID categoryId, RenameCategory command, Actor actor) {
        actor.requireWrite(Section.FINANCE);
        validator.requireValid(command);
        var name = requireName(command.name());
        // Вид не меняется: по нему берётся блокировка списка до блокировки строки, в порядке CategoryLocks.
        var kind = categories.findKindById(categoryId).orElseThrow(() -> new CategoryNotFoundException(categoryId));
        locks.categories(kind);
        var category = requireLocked(categoryId);
        if (categories.countByNameExcept(kind, name, categoryId) > 0) {
            throw new CategoryNameTakenException(name);
        }
        category.rename(name);
        try {
            categories.flush();
        } catch (DataIntegrityViolationException e) {
            throw nameTakenOr(e, name);
        }
        return category.toView();
    }

    @Override
    public void hide(UUID categoryId, Actor actor) {
        actor.requireWrite(Section.FINANCE);
        requireLocked(categoryId).setHidden(true);
    }

    @Override
    public void show(UUID categoryId, Actor actor) {
        actor.requireWrite(Section.FINANCE);
        requireLocked(categoryId).setHidden(false);
    }

    @Override
    public void reorder(EntryKind kind, List<UUID> orderedIds, Actor actor) {
        actor.requireWrite(Section.FINANCE);
        requireKind(kind);
        if (orderedIds.stream().anyMatch(Objects::isNull)) {
            throw new ValidationException("orderedIds", "must not contain nulls");
        }
        locks.categories(kind);
        var all = categories.findAllLockedByKind(kind).stream()
                .collect(Collectors.toMap(CategoryEntity::id, Function.identity()));
        // Размер и множество совпадают — значит, каждая категория ровно один раз и лишних нет.
        if (orderedIds.size() != all.size() || !new HashSet<>(orderedIds).equals(all.keySet())) {
            throw new ValidationException("orderedIds", "must list every category of the kind exactly once");
        }
        // Посреди перестановки позиции повторяются; проверка уникальности — после всех изменений, но до выхода.
        categories.deferPositionCheck();
        var position = 0;
        for (var id : orderedIds) {
            all.get(id).setPosition(position++);
        }
        categories.checkPositions();
    }

    private CategoryEntity requireLocked(UUID categoryId) {
        return categories.findLockedById(categoryId).orElseThrow(() -> new CategoryNotFoundException(categoryId));
    }

    private static void requireKind(EntryKind kind) {
        // Вызывающий код на Java может передать null мимо проверок HTTP-слоя: ошибка запроса, а не сбой.
        if (kind == null) {
            throw new ValidationException("kind", "must not be null");
        }
    }

    /**
     * Название без пробелов по краям: «Аренда » и «Аренда» — одно название. Длина — в символах (кодовых точках), как
     * {@code char_length} в {@code CHECK} базы: эмодзи — один символ, хотя в Java это две единицы UTF-16.
     */
    private static String requireName(String name) {
        var stripped = name.strip();
        if (stripped.isEmpty()) {
            throw new ValidationException("name", "must not be blank");
        }
        if (stripped.codePointCount(0, stripped.length()) > MAX_NAME_LENGTH) {
            throw new ValidationException("name", "must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return stripped;
    }

    /**
     * Нарушение уникальности названия — запись в обход блокировки списка успела раньше; остальное — ошибка в коде или
     * схеме. После нарушения транзакция PostgreSQL прервана: исключению дают всплыть.
     */
    private static RuntimeException nameTakenOr(DataIntegrityViolationException e, String name) {
        var constraint = ConstraintViolations.constraintName(e);
        if (constraint.filter(NAME_KEY::equals).isPresent()) {
            return new CategoryNameTakenException(name);
        }
        return new InternalException("category write violated constraint " + constraint.orElse("<unknown>"), e);
    }
}
