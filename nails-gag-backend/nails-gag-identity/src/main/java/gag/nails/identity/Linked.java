package gag.nails.identity;

import java.util.UUID;

/** Способ входа привязан к аккаунту (или уже был его). */
public record Linked(UUID accountId) implements LoginResult {}
