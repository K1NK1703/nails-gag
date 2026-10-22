package gag.nails.api.internal.booking;

import gag.nails.api.internal.http.CacheControls;
import gag.nails.api.internal.http.ImageResponses;
import gag.nails.booking.BookingReferences;
import gag.nails.booking.model.ReferenceView;
import gag.nails.common.actor.Actor;
import gag.nails.files.FileStorage;
import gag.nails.files.model.FilePurpose;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Фото-референсы к записи (спецификация файлов и портфолио, §6.3): до {@value BookingReferences#LIMIT} на запись.
 * Видят их те, кто видит запись; прикладывает и убирает клиент своей записи до начала визита и сотрудник с изменением
 * «Записей» всегда. Права и правила проверяет модуль записей; чужая запись клиенту — {@code 404 common.not_found}.
 * Фронт сначала создаёт запись, затем прикладывает загруженные фото.
 */
@RestController
@RequestMapping("/api/bookings/{id}/references")
class ReferencesController {

    private final BookingReferences references;
    private final FileStorage files;

    ReferencesController(BookingReferences references, FileStorage files) {
        this.references = references;
        this.files = files;
    }

    /** Референсы записи в порядке добавления. */
    @GetMapping
    List<ReferenceView> list(@PathVariable UUID id, Actor actor) {
        return references.list(id, actor);
    }

    /**
     * Прикладывает загруженное этим же аккаунтом фото. Клиент после начала визита или к неактивной записи —
     * {@code 409 booking.references_closed}; уже {@value BookingReferences#LIMIT} — {@code 409}
     * {@code booking.references_limit_reached}; чужое, занятое или не то фото — {@code 400}.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ReferenceView attach(@PathVariable UUID id, @Valid @RequestBody AttachReference body, Actor actor) {
        return references.add(id, body.fileId(), actor);
    }

    /**
     * Убирает референс, кто бы его ни приложил, и удаляет фото; такого референса нет — ничего не делает. Клиент после
     * начала визита — {@code 409 booking.references_closed}.
     */
    @DeleteMapping("/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable UUID id, @PathVariable UUID fileId, Actor actor) {
        references.remove(id, fileId, actor);
    }

    /**
     * Фото референса в JPEG, не шире 2000 px, без кэша. Фото не референс этой записи — {@code 404} без различия
     * причин.
     */
    @GetMapping(path = "/{fileId}.jpg", produces = MediaType.IMAGE_JPEG_VALUE)
    ResponseEntity<InputStreamResource> image(@PathVariable UUID id, @PathVariable UUID fileId, Actor actor) {
        if (!references.imageVisible(id, fileId, actor)) {
            throw ImageResponses.notFound();
        }
        var variant = files.open(fileId, FilePurpose.REFERENCE.maxWidth()).orElseThrow(ImageResponses::notFound);
        return ImageResponses.of(variant, CacheControls.NO_STORE);
    }
}
