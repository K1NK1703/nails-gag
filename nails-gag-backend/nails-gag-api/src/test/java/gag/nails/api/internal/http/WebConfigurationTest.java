package gag.nails.api.internal.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.model.DocumentKind;
import gag.nails.common.actor.Section;
import gag.nails.identity.model.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.format.support.DefaultFormattingConversionService;

/** Перечисления в пути и параметрах запроса: имя в нижнем регистре через дефис (спецификация API, §7.7). */
class WebConfigurationTest {

    private final DefaultFormattingConversionService conversions = new DefaultFormattingConversionService();

    @BeforeEach
    void registerFormatters() {
        new WebConfiguration().addFormatters(conversions);
    }

    @Test
    void sectionIsKebabCase() {
        assertThat(conversions.convert("booking-settings", Section.class)).isEqualTo(Section.BOOKING_SETTINGS);
        assertThat(conversions.convert("catalog", Section.class)).isEqualTo(Section.CATALOG);
    }

    @Test
    void javaNamesAndUnknownValuesAreRejected() {
        for (var value : new String[] {"BOOKING_SETTINGS", "booking_settings", "Catalog", "finance-x", ""}) {
            assertThatThrownBy(() -> conversions.convert(value, Section.class))
                    .as(value)
                    .isInstanceOf(ConversionFailedException.class);
        }
    }

    @Test
    void providerAndDocumentStayLowerCase() {
        assertThat(conversions.convert("yandex", Provider.class)).isEqualTo(Provider.YANDEX);
        assertThat(conversions.convert("fake", Provider.class)).isEqualTo(Provider.FAKE);
        assertThat(conversions.convert("policy", DocumentKind.class)).isEqualTo(DocumentKind.POLICY);
        assertThat(conversions.convert("offer", DocumentKind.class)).isEqualTo(DocumentKind.OFFER);
    }

    @Test
    void kebabNameIsLowerCaseWithHyphens() {
        assertThat(WebConfiguration.kebabCase(Section.BOOKING_SETTINGS.name())).isEqualTo("booking-settings");
        assertThat(WebConfiguration.kebabCase(Provider.VK.name())).isEqualTo("vk");
    }
}
