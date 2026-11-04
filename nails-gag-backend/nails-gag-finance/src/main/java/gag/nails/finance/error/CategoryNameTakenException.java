package gag.nails.finance.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;

/** Категория с таким названием (без учёта регистра) в этом виде уже есть. */
public final class CategoryNameTakenException extends UserFacingException {

    public CategoryNameTakenException(String name) {
        super("finance.category_name_taken", Map.of("name", name));
    }
}
