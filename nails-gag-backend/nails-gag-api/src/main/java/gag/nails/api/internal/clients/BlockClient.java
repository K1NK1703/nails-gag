package gag.nails.api.internal.clients;

import org.jspecify.annotations.Nullable;

/**
 * Тело {@code POST /api/clients/{id}/block}.
 *
 * @param reason причина блокировки, клиенту не видна; пустая не сохраняется. Длину (до 500 символов) проверяет модуль
 *     после проверки прав — {@code 400 common.validation} с полем {@code reason}
 */
record BlockClient(@Nullable String reason) {}
