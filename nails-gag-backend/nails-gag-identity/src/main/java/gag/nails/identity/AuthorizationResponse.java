package gag.nails.identity;

import org.jspecify.annotations.Nullable;

/**
 * Ответ провайдера на возврате: {@code code} и {@code state} из запроса; {@code deviceId} отдаёт только
 * VK ID, у остальных — {@code null}.
 */
public record AuthorizationResponse(
        String code, String state, @Nullable String deviceId) {}
