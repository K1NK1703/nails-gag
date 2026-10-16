package gag.nails.api.internal.auth;

/** Ответ {@code POST /api/auth/fake/code}: одноразовый код подставного провайдера для обратного вызова. */
record FakeCode(String code) {}
