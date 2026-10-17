package gag.nails.api.internal.schedule;

import jakarta.validation.constraints.NotNull;
import java.time.LocalTime;

/**
 * Тело {@code POST /api/schedule/slots/{id}/move}: новое время свободного слота в тот же день.
 *
 * @param startTime время начала в поясе салона, {@code "11:00"}; длительность слота сохраняется
 */
record MoveSlot(@NotNull LocalTime startTime) {}
