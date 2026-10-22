package gag.nails.api.internal.portfolio;

import static gag.nails.api.internal.http.CacheControls.PUBLIC;

import gag.nails.api.internal.http.PageDefaults;
import gag.nails.common.paging.Paged;
import gag.nails.common.paging.Paging;
import gag.nails.portfolio.PortfolioQueries;
import gag.nails.portfolio.model.PublicWork;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Раздел «Работы» на лендинге (спецификация файлов и портфолио, §6.2): опубликованные работы без входа, кэш на 5
 * минут, как у прайса. Ведение работ — {@link WorksController}, картинки — {@link PortfolioImagesController}.
 */
@RestController
class PortfolioController {

    /** Размер страницы по умолчанию: 12 делится на 2, 3 и 4 колонки сетки лендинга. */
    static final String PAGE_SIZE = "12";

    private final PortfolioQueries portfolio;

    PortfolioController(PortfolioQueries portfolio) {
        this.portfolio = portfolio;
    }

    /**
     * Опубликованные работы по порядку мастера, постранично: без клиента, записи и источника. Адреса картинок фронт
     * строит из {@code fileId}. Номер и размер страницы проверяет {@link Paging}.
     */
    @GetMapping("/api/portfolio")
    ResponseEntity<Paged<PublicWork>> published(
            @RequestParam(defaultValue = PageDefaults.FIRST_PAGE) int page,
            @RequestParam(defaultValue = PAGE_SIZE) int size) {
        return ResponseEntity.ok().cacheControl(PUBLIC).body(portfolio.published(new Paging(page, size)));
    }
}
