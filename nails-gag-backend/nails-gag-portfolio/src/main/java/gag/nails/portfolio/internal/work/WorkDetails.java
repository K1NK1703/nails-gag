package gag.nails.portfolio.internal.work;

import gag.nails.portfolio.model.WorkSource;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Изменяемые поля работы после проверок: подпись нормализована, клиент взят из записи.
 *
 * @param caption подпись; пустой не бывает
 * @param bookingId есть только у {@link WorkSource#CLIENT}
 * @param clientId клиент записи; есть только у {@link WorkSource#CLIENT}
 */
record WorkDetails(
        String alt,
        @Nullable String caption,
        WorkSource source,
        @Nullable UUID bookingId,
        @Nullable UUID clientId) {}
