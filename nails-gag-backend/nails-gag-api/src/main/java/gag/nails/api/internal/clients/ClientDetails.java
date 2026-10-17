package gag.nails.api.internal.clients;

import gag.nails.clients.model.ClientView;
import gag.nails.clients.model.PrepaymentFlagChangeView;
import org.jspecify.annotations.Nullable;

/**
 * Карточка клиента целиком (спецификация API, §7.4): ответ {@code GET /api/clients/{id}}.
 *
 * @param client карточка
 * @param blockReason причина блокировки; только сотруднику с чтением раздела «Клиенты», иначе {@code null}
 * @param masterNote заметка мастера; только сотруднику с чтением раздела «Клиенты», иначе {@code null}
 * @param lastPrepaymentChange последнее изменение признака предоплаты: почему клиенту нужна предоплата
 */
record ClientDetails(
        ClientView client,
        @Nullable String blockReason,
        @Nullable String masterNote,
        @Nullable PrepaymentFlagChangeView lastPrepaymentChange) {}
