package gag.nails.api.internal.booking;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Тело {@code POST /api/bookings/{id}/references}: фото-референс к записи.
 *
 * @param fileId фото, загруженное этим же аккаунтом с назначением {@code reference} и ещё не занятое
 */
record AttachReference(@NotNull UUID fileId) {}
