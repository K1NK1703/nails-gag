package gag.nails.api.internal.schedule;

/**
 * Тело {@code PUT /api/schedule/settings/slot-duration}: длительность новых слотов.
 *
 * @param minutes длительность в минутах. Диапазон (15–720) проверяет модуль после проверки прав —
 *     {@code 400 common.validation} с полем {@code minutes}
 */
record SetSlotDuration(int minutes) {}
