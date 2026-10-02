package gag.nails.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Слот расписания. Существующий слот открыт для записи; занятый несёт идентификатор записи.
 *
 * @param day дата начала в поясе салона
 * @param durationMinutes снимок настройки на момент создания; смена настройки слот не меняет
 * @param bookingId запись, занявшая слот; {@code null}, пока слот свободен
 */
public record SlotView(
        UUID id,
        LocalDate day,
        Instant startsAt,
        Instant endsAt,
        int durationMinutes,
        boolean occupied,
        @Nullable UUID bookingId) {}
