package gag.nails.identity.model;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Section;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Права аккаунта для раздела «Доступ».
 *
 * @param displayName имя из последнего использованного способа входа
 * @param overrides личные переопределения, включая явный {@link AccessLevel#NONE}
 * @param effective итог по всем девяти разделам: переопределение, иначе максимум по ролям, иначе
 *     {@link AccessLevel#NONE}
 * @param blocked карточка клиента заблокирована
 */
public record AccountAccessView(
        UUID accountId,
        @Nullable UUID clientId,
        @Nullable String displayName,
        List<RoleView> roles,
        Map<Section, AccessLevel> overrides,
        Map<Section, AccessLevel> effective,
        boolean blocked) {}
