package gag.nails.api.internal.http;

import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

/**
 * Перечисления в пути и параметрах запроса всех контроллеров — только через дефис
 * ({@link WebConfiguration#registerKebabCaseEditors}): привязка каждого аргумента получает редакторы этих типов.
 */
@ControllerAdvice
class KebabCaseBinding {

    @InitBinder
    void kebabCaseEnums(WebDataBinder binder) {
        WebConfiguration.registerKebabCaseEditors(binder);
    }
}
