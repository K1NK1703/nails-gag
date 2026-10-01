package gag.nails.clients;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Согласие, которое фиксируется в журнале.
 *
 * @param documentVersion версия документа, которую показали под галочкой; должна быть действующей
 */
public record RecordConsent(
        @NotNull ConsentKind kind,
        @NotBlank String documentVersion,
        @NotNull ConsentSource source) {}
