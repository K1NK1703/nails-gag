package gag.nails.identity.model;

import gag.nails.identity.LoginFlow;

/**
 * Способ входа принадлежит другому аккаунту: привязка объединит аккаунты, нужно подтверждение.
 * {@code ticket} — одноразовый билет на 30 минут для {@link LoginFlow#confirmLink}.
 */
public record LinkConfirmationRequired(String ticket) implements LoginResult {}
