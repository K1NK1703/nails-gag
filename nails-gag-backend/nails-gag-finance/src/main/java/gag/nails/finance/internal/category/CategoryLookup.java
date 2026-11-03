package gag.nails.finance.internal.category;

import gag.nails.common.error.InternalException;
import gag.nails.finance.error.CategoryNotFoundException;
import gag.nails.finance.model.EntryKind;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Категории для записей. Только внутри транзакции записи ({@code MANDATORY}): разделяемая блокировка строки
 * категории держится до её конца.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class CategoryLookup {

    private final CategoryRepository categories;
    private final CategoryLocks locks;

    CategoryLookup(CategoryRepository categories, CategoryLocks locks) {
        this.categories = categories;
        this.locks = locks;
    }

    /**
     * Блокировка списка категорий вида ({@link CategoryLocks#categories}) — первая в порядке блокировок модуля. Её
     * берёт правка шаблона, которая может держать две категории вида: так она не встаёт накрест с перестановкой.
     */
    public void lockCategoriesOf(EntryKind kind) {
        locks.categories(kind);
    }

    /**
     * Категория, которую ставят записи, под разделяемой блокировкой строки ({@code FOR SHARE}): пока запись
     * проверяет видимость категории и запоминает название в журнале, категорию не скроют и не переименуют.
     *
     * @throws CategoryNotFoundException если категории нет
     */
    public CategoryRef forEntry(UUID categoryId) {
        return categories
                .findSharedById(categoryId)
                .map(CategoryEntity::toRef)
                .orElseThrow(() -> new CategoryNotFoundException(categoryId));
    }

    /**
     * Текущее название категории без блокировки — для журнала, когда запись уходит из этой категории. Категории не
     * удаляются, поэтому категория записи всегда есть.
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public String name(UUID categoryId) {
        return categories
                .findById(categoryId)
                .map(CategoryEntity::name)
                .orElseThrow(() -> new InternalException("category " + categoryId + " of an entry is missing"));
    }
}
