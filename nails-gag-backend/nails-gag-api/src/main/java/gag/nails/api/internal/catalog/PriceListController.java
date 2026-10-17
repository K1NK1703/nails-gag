package gag.nails.api.internal.catalog;

import gag.nails.catalog.CatalogQueries;
import gag.nails.catalog.model.ServiceView;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Прайс на лендинге (спецификация API, §7.3): публичен и кэшируется на 5 минут, как документы. Услуги — активные с
 * признаком показа на лендинге, в порядке прайса.
 */
@RestController
class PriceListController {

    /** Ответ один для всех, свой {@code Cache-Control} заменяет {@code no-store} фильтра. */
    private static final CacheControl PUBLIC =
            CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private final CatalogQueries catalog;

    PriceListController(CatalogQueries catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/api/price-list")
    ResponseEntity<List<ServiceView>> priceList() {
        return ResponseEntity.ok().cacheControl(PUBLIC).body(catalog.landingPriceList());
    }
}
