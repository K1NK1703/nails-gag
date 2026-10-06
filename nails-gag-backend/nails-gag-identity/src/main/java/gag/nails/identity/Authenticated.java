package gag.nails.identity;

import gag.nails.common.actor.Actor;

/** Вошедший: {@code Actor} для проверок прав и сведения об аккаунте для интерфейса. */
public record Authenticated(Actor actor, AccountView account) {}
