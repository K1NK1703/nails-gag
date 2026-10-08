package gag.nails.identity.model;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Section;
import gag.nails.identity.Accounts;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Аккаунт для интерфейса.
 *
 * @param clientId карточка клиента; пусто, пока регистрация не завершена
 * @param displayName имя из способа входа текущей сессии (в {@link Accounts#me} — последнего
 *     использованного)
 * @param avatarUrl аватарка того же способа входа
 * @param loginMethods по времени привязки
 * @param access итоговые уровни по разделам; отсутствующий раздел — {@link AccessLevel#NONE}
 */
public record AccountView(
        UUID id,
        @Nullable UUID clientId,
        @Nullable String displayName,
        @Nullable String avatarUrl,
        List<LoginMethodView> loginMethods,
        Map<Section, AccessLevel> access,
        Instant createdAt) {}
