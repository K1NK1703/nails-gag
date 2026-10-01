package gag.nails.clients;

import gag.nails.clients.error.DocumentVersionExistsException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import java.time.LocalDate;
import java.util.List;

/**
 * Редакции политики и оферты. Действующая — последняя, чья дата начала не позже сегодняшнего дня
 * по часам салона. Новую редакцию добавляет мастер, когда меняется текст документа на сайте.
 */
public interface DocumentVersionService {

    /** Действующая редакция; стартовые данные гарантируют, что она есть у каждого документа. */
    DocumentVersionView current(DocumentKind kind);

    /** Все редакции документа, новые первыми. */
    List<DocumentVersionView> history(DocumentKind kind);

    /**
     * Только мастер ({@link AccessDeniedException} иначе).
     *
     * @throws DocumentVersionExistsException если такая версия уже есть
     */
    DocumentVersionView addVersion(DocumentKind kind, String version, LocalDate effectiveFrom, Actor actor);
}
