package gag.nails.clients.command;

import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentSource;
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
