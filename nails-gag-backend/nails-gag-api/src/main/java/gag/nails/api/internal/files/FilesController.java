package gag.nails.api.internal.files;

import gag.nails.api.internal.http.ApiPaths;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.files.FileStorage;
import gag.nails.files.model.FilePurpose;
import gag.nails.files.model.StoredFileView;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Загрузка фото (спецификация файлов и портфолио, §6.1). Модуль файлов о правах на назначение не знает — их проверяет
 * этот адрес; привязку файла к работе или записи проверяет её модуль. Загрузка — «сирота», пока владелец не
 * привяжет её; непривязанную через сутки удаляет уборка. Своя корзина лимита частоты — {@code uploads-per-hour}.
 */
@RestController
class FilesController {

    private final FileStorage files;

    FilesController(FileStorage files) {
        this.files = files;
    }

    /**
     * Картинка JPEG, PNG или WebP в поле {@code file}: формат — по содержимому, имя и заявленный тип не важны.
     * Референс загружает любой вошедший, фото портфолио — с изменением раздела «Портфолио». Файл больше 15 МБ —
     * {@code 413}; не картинка или больше 50 Мп — {@code 400}; обработка занята — {@code 503} с {@code Retry-After}.
     * Транзакции нет: обработка идёт секундами и не держит соединение с базой.
     */
    @PostMapping(path = ApiPaths.FILES, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    StoredFileView upload(@RequestParam FilePurpose purpose, @RequestPart MultipartFile file, Actor actor)
            throws IOException {
        if (purpose == FilePurpose.PORTFOLIO) {
            actor.requireWrite(Section.PORTFOLIO);
        }
        try (var content = file.getInputStream()) {
            return files.store(content, file.getSize(), purpose, actor);
        }
    }
}
