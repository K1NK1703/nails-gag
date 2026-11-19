package gag.nails.notifications.command;

import gag.nails.notifications.model.DeliveryStatus;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Отбор журнала доставок.
 *
 * @param status только в этом состоянии; пусто — в любом
 * @param bookingId только по этой записи; пусто — по любой
 */
public record DeliveryFilter(
        @Nullable DeliveryStatus status, @Nullable UUID bookingId) {}
