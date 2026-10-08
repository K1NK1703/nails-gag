package gag.nails.identity.model;

import org.jspecify.annotations.Nullable;

/** Что знает HTTP-слой о запросе для журнала входов; оба поля могут быть пустыми. */
public record ClientInfo(@Nullable String ip, @Nullable String userAgent) {}
