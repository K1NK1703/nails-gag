package gag.nails.portfolio.model;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Работа для админки: со скрытыми, источником и согласием.
 *
 * @param fileId файл фото
 * @param alt описание фото, 1–200 символов
 * @param caption подпись до 200 символов; может отсутствовать
 * @param position место на лендинге: меньше — раньше
 * @param visible мастер показывает работу; опубликована она, только если ещё и {@code consent} позволяет
 * @param bookingId запись, к которой относится работа; есть только у {@link WorkSource#CLIENT}
 * @param clientId клиент записи; есть только у {@link WorkSource#CLIENT}
 * @param width ширина фото (до уменьшения)
 * @param height высота фото (до уменьшения)
 * @param published работа видна на лендинге: {@code visible} и согласие не {@link WorkConsent#MISSING}
 * @param consent состояние согласия на публикацию
 */
public record WorkView(
        UUID id,
        UUID fileId,
        String alt,
        @Nullable String caption,
        int position,
        boolean visible,
        WorkSource source,
        @Nullable UUID bookingId,
        @Nullable UUID clientId,
        int width,
        int height,
        boolean published,
        WorkConsent consent,
        Instant createdAt) {}
