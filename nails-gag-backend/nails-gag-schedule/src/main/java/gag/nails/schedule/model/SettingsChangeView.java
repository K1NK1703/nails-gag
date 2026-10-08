package gag.nails.schedule.model;

import java.time.Instant;

/** Строка журнала настроек: какое поле, что было, что стало, когда. */
public record SettingsChangeView(String field, String oldValue, String newValue, Instant changedAt) {}
