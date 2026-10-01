package gag.nails.clients;

import java.time.Instant;

/** Запись журнала согласий: что, по какой версии документа, когда и откуда. */
public record ConsentView(ConsentKind kind, String documentVersion, Instant givenAt, ConsentSource source) {}
