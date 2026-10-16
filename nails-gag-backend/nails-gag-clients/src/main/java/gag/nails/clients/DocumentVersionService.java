package gag.nails.clients;

import gag.nails.clients.command.AddDocumentVersion;
import gag.nails.clients.error.DocumentVersionDateTakenException;
import gag.nails.clients.error.DocumentVersionExistsException;
import gag.nails.clients.error.DocumentVersionNotFoundException;
import gag.nails.clients.model.DocumentKind;
import gag.nails.clients.model.DocumentVersionText;
import gag.nails.clients.model.DocumentVersionView;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import java.util.List;

/**
 * Редакции политики и оферты с их текстом. Действующая — последняя, чья дата начала не позже
 * сегодняшнего дня по часам салона. Новую редакцию добавляет сотрудник с уровнем изменения раздела
 * «Документы», когда меняется текст документа на сайте; у мастера там только чтение.
 */
public interface DocumentVersionService {

    /** Действующая редакция; стартовые данные гарантируют, что она есть у каждого документа. */
    DocumentVersionView current(DocumentKind kind);

    /** Все редакции документа, новые первыми. */
    List<DocumentVersionView> history(DocumentKind kind);

    /**
     * Редакция с текстом. Нужен уровень чтения раздела «Документы» ({@link AccessDeniedException} иначе).
     *
     * @throws DocumentVersionNotFoundException у документа нет такой редакции
     */
    DocumentVersionText version(DocumentKind kind, String version, Actor actor);

    /**
     * Нужен уровень изменения раздела «Документы» ({@link AccessDeniedException} иначе).
     *
     * @throws DocumentVersionExistsException если такая версия уже есть
     * @throws DocumentVersionDateTakenException если у документа уже есть редакция с этой датой начала
     */
    DocumentVersionView addVersion(AddDocumentVersion command, Actor actor);
}
