package gag.nails.api.internal.auth;

import java.net.URI;

/** Ответ {@code start}: адрес провайдера, куда фронт отправляет браузер. */
record LoginRedirect(URI authorizationUri) {}
