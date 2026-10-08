package gag.nails.identity.model;

import java.util.UUID;

/** Способ входа привязан к аккаунту (или уже был его). */
public record Linked(UUID accountId) implements LoginResult {}
