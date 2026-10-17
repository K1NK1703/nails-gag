package gag.nails.api.internal.documents;

import static gag.nails.api.internal.http.CacheControls.PUBLIC;

import gag.nails.clients.DocumentVersionService;
import gag.nails.clients.model.DocumentKind;
import gag.nails.clients.model.DocumentVersionText;
import gag.nails.clients.model.DocumentVersionView;
import gag.nails.common.actor.Actor;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Редакции политики и оферты (спецификация API, §7.2). Списки редакций без текста публичны и кэшируются на 5 минут,
 * как сами документы на сайте; текст редакции и новая редакция — по уровню раздела «Документы», его проверяет модуль.
 * Документ в пути — {@code policy} или {@code offer}; другое имя с адресом не совпадает и даёт {@code 404}.
 */
@RestController
@RequestMapping("/api/documents")
class DocumentsController {

    private static final String KIND = "/{kind:policy|offer}";

    private final DocumentVersionService documents;

    DocumentsController(DocumentVersionService documents) {
        this.documents = documents;
    }

    /** Действующие редакции в порядке {@link DocumentKind#values()}. */
    @GetMapping
    ResponseEntity<List<DocumentVersionView>> current() {
        var current =
                Arrays.stream(DocumentKind.values()).map(documents::current).toList();
        return ResponseEntity.ok().cacheControl(PUBLIC).body(current);
    }

    /** Все редакции документа, новые первыми. */
    @GetMapping(KIND + "/versions")
    ResponseEntity<List<DocumentVersionView>> history(@PathVariable DocumentKind kind) {
        return ResponseEntity.ok().cacheControl(PUBLIC).body(documents.history(kind));
    }

    /** Редакция с полным текстом; нужен уровень чтения раздела «Документы». */
    @GetMapping(KIND + "/versions/{version}")
    DocumentVersionText version(@PathVariable DocumentKind kind, @PathVariable String version, Actor actor) {
        return documents.version(kind, version, actor);
    }

    /** Новая редакция документа; нужен уровень изменения раздела «Документы». */
    @PostMapping(KIND + "/versions")
    @ResponseStatus(HttpStatus.CREATED)
    DocumentVersionView addVersion(
            @PathVariable DocumentKind kind, @Valid @RequestBody NewDocumentVersion body, Actor actor) {
        return documents.addVersion(body.toCommand(kind), actor);
    }
}
