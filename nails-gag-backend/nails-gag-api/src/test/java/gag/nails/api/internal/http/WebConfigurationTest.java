package gag.nails.api.internal.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.model.DocumentKind;
import gag.nails.common.actor.Section;
import gag.nails.common.error.ValidationException;
import gag.nails.identity.model.Provider;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.WebDataBinder;

/**
 * Перечисления в пути и параметрах запроса: имя в нижнем регистре через дефис (спецификация API, §7.7). Разбор — тем
 * же путём, что у аргумента контроллера: привязка с именем параметра и редакторами слоя.
 */
class WebConfigurationTest {

    @Test
    void sectionIsKebabCase() {
        var binder = binder("section");

        assertThat(binder.convertIfNecessary("booking-settings", Section.class)).isEqualTo(Section.BOOKING_SETTINGS);
        assertThat(binder.convertIfNecessary("catalog", Section.class)).isEqualTo(Section.CATALOG);
    }

    /** Отказ — ошибка проверки с именем параметра: так его покажет {@code ProblemHandler}. */
    @Test
    void javaNamesAndUnknownValuesAreRejected() {
        var binder = binder("section");

        for (var value :
                new String[] {"BOOKING_SETTINGS", "booking_settings", "Catalog", " catalog", "finance-x", ""}) {
            assertThatThrownBy(() -> binder.convertIfNecessary(value, Section.class))
                    .as(value)
                    .isInstanceOfSatisfying(
                            ValidationException.class,
                            exception -> assertThat(exception.field()).isEqualTo("section"));
        }
    }

    @Test
    void providerAndDocumentStayLowerCase() {
        var binder = binder("value");

        assertThat(binder.convertIfNecessary("yandex", Provider.class)).isEqualTo(Provider.YANDEX);
        assertThat(binder.convertIfNecessary("fake", Provider.class)).isEqualTo(Provider.FAKE);
        assertThat(binder.convertIfNecessary("policy", DocumentKind.class)).isEqualTo(DocumentKind.POLICY);
        assertThat(binder.convertIfNecessary("offer", DocumentKind.class)).isEqualTo(DocumentKind.OFFER);
        assertThat(binder.convertIfNecessary("history", BookingScope.class)).isEqualTo(BookingScope.HISTORY);
    }

    @Test
    void kebabNameIsLowerCaseWithHyphens() {
        assertThat(WebConfiguration.kebabCase(Section.BOOKING_SETTINGS.name())).isEqualTo("booking-settings");
        assertThat(WebConfiguration.kebabCase(Provider.VK.name())).isEqualTo("vk");
    }

    /** Привязка одного аргумента, как её готовит Spring MVC: имя объекта — имя параметра. */
    private static WebDataBinder binder(String parameter) {
        var binder = new WebDataBinder(null, parameter);
        WebConfiguration.registerKebabCaseEditors(binder);
        return binder;
    }
}
