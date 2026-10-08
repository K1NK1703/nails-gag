package gag.nails.identity.model;

import java.net.URI;

/** Начатый вход: адрес провайдера для перехода и состояние, которое HTTP-слой вернёт в {@code complete}. */
public record LoginStart(URI authorizationUri, LoginState state) {}
