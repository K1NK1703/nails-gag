package gag.nails.api.internal.schedule;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Тело {@code POST /api/schedule/slots}: новый слот. Длительность — из текущей настройки, пересечение с другими слотами
 * проверяет модуль.
 *
 * @param date дата в поясе салона; прошедшая допустима — так вносится визит без записи
 * @param startTime время начала в поясе салона, {@code "10:00"}
 */
record AddSlot(@NotNull LocalDate date, @NotNull LocalTime startTime) {}
