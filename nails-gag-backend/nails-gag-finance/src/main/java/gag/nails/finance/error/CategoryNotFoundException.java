package gag.nails.finance.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;
import java.util.UUID;

/** Категории с таким идентификатором нет. */
public final class CategoryNotFoundException extends UserFacingException {

    public CategoryNotFoundException(UUID categoryId) {
        super("finance.category_not_found", Map.of("categoryId", categoryId));
    }
}
