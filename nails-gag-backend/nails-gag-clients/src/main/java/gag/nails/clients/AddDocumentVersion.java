package gag.nails.clients;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Новая редакция документа.
 *
 * @param version метка версии, как она показана на сайте (например, {@code 2026-09-25}); уникальна в рамках документа
 * @param effectiveFrom день, с которого редакция действует; у документа не бывает двух редакций с одной датой
 */
public record AddDocumentVersion(
        @NotNull DocumentKind kind,
        @NotBlank @Size(max = 64) String version,
        @NotNull LocalDate effectiveFrom) {}
