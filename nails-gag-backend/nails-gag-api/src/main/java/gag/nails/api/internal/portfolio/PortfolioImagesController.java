package gag.nails.api.internal.portfolio;

import gag.nails.api.internal.http.ApiPaths;
import gag.nails.api.internal.http.CacheControls;
import gag.nails.api.internal.http.ImageBody;
import gag.nails.api.internal.http.ImageResponses;
import gag.nails.common.actor.Actor;
import gag.nails.files.FileStorage;
import gag.nails.portfolio.PortfolioQueries;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Картинки работ портфолио (спецификация файлов и портфолио, §6.2): опубликованная — всем с публичным кэшем на час,
 * прочие — сотруднику с чтением «Портфолио» без кэша. Правило публичности — то же, что у списка на лендинге.
 */
@RestController
class PortfolioImagesController {

    private final PortfolioQueries portfolio;
    private final FileStorage files;

    PortfolioImagesController(PortfolioQueries portfolio, FileStorage files) {
        this.portfolio = portfolio;
        this.files = files;
    }

    /**
     * Вариант фото шириной 480, 960 или 1600 px (уже, если оригинал уже) в JPEG. Опубликованная работа —
     * {@code Cache-Control: public, max-age=3600}; скрытая, без согласия или ещё не сохранённая работа видна только
     * сотруднику с чтением «Портфолио», с {@code no-store}. Нет такой картинки, ширины или прав — {@code 404} без
     * различия причин. {@code ETag} — {@code "<fileId>-<width>"}: повтор с {@code If-None-Match} получает {@code 304}
     * (после проверки видимости). {@code Range} не поддерживается — ответ всегда целиком.
     *
     * <p>Публичность проверяется первой: она же решает кэш, поэтому опубликованная картинка не уходит в публичный
     * кэш по правам сотрудника.
     */
    @GetMapping(path = ApiPaths.PORTFOLIO_IMAGES + "/{fileId}/{width}.jpg", produces = MediaType.IMAGE_JPEG_VALUE)
    @ApiResponse(
            responseCode = "200",
            description = "OK",
            content =
                    @Content(
                            mediaType = MediaType.IMAGE_JPEG_VALUE,
                            schema = @Schema(type = "string", format = "binary")))
    ResponseEntity<ImageBody> image(@PathVariable UUID fileId, @PathVariable int width, Optional<Actor> actor) {
        var published = portfolio.imagePublished(fileId);
        // Аноним видит только опубликованное: второй запрос о том же ему не нужен.
        if (!published && !(actor.isPresent() && portfolio.imageVisible(fileId, actor))) {
            throw ImageResponses.notFound();
        }
        var variant = files.open(fileId, width).orElseThrow(ImageResponses::notFound);
        return ImageResponses.of(
                fileId, width, variant, published ? CacheControls.PUBLIC_IMAGE : CacheControls.NO_STORE);
    }
}
