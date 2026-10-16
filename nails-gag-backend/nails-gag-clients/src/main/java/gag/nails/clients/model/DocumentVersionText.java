package gag.nails.clients.model;

import org.jspecify.annotations.Nullable;

/**
 * Редакция документа и её полный текст.
 *
 * @param text пусто только у стартовых редакций, заведённых до хранения текста
 */
public record DocumentVersionText(
        DocumentVersionView version, @Nullable String text) {}
