package gag.nails.clients;

import java.time.LocalDate;
import java.util.UUID;

/** Редакция документа: метка версии (например, {@code 2026-09-25}) и дата, с которой действует. */
public record DocumentVersionView(UUID id, DocumentKind kind, String version, LocalDate effectiveFrom) {}
