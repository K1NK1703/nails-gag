package gag.nails.booking;

import java.time.Instant;

/** Строка журнала правил записи: какое поле, что было, что стало, когда. */
public record BookingSettingsChangeView(String field, String oldValue, String newValue, Instant changedAt) {}
