package gag.nails.api.internal.documents;

import gag.nails.clients.command.AddDocumentVersion;
import gag.nails.clients.model.DocumentKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Тело {@code POST /api/documents/{kind}/versions}: {@link AddDocumentVersion} без документа — он в пути. Ограничения
 * те же, что у команды.
 */
record NewDocumentVersion(
        @NotBlank @Size(max = 64) String version,
        @NotNull LocalDate effectiveFrom,
        @NotBlank @Size(max = 200_000) String text) {

    AddDocumentVersion toCommand(DocumentKind kind) {
        return new AddDocumentVersion(kind, version, effectiveFrom, text);
    }
}
