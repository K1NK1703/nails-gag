package gag.nails.api.internal.clients;

import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Тело {@code POST /api/clients/{id}/block}.
 *
 * @param reason причина блокировки, клиенту не видна; до 500 символов, как в колонке модуля; пустая не сохраняется
 */
record BlockClient(@Size(max = 500) @Nullable String reason) {}
