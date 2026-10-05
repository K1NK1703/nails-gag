package gag.nails.booking;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Новые значения всех правил записи сразу; в журнал попадают только изменившиеся. */
public record UpdateBookingSettings(
        @Min(1) @Max(10) int maxActiveBookings,
        @Min(0) @Max(10080) int bookingCutoffMinutes,
        @Min(1) @Max(168) int prepaymentHoldHours,
        @Min(0) @Max(168) int freeCancelWindowHours,
        @Min(1) @Max(100) int prepaymentPercent,
        @Min(0) @Max(120) int lateArrivalToleranceMinutes,
        @Min(0) @Max(60) int markCorrectionWindowDays) {}
