package gag.nails.clients.model;

import java.util.UUID;

/** Кто остался после объединения и кто удалён. Остаётся клиент с аккаунтом, если он есть у одного из двух. */
public record MergeResult(UUID survivorId, UUID removedId) {}
