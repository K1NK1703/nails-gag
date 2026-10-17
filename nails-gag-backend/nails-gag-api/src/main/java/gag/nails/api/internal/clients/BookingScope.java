package gag.nails.api.internal.clients;

/**
 * Какие записи клиента показать ({@code GET /api/clients/{id}/bookings?scope=…}). В адресе — в нижнем регистре
 * ({@code active}, {@code history}), конвертер в {@code internal.http}; поэтому публичное.
 */
public enum BookingScope {
    /** Активные записи, ближайшие первыми. */
    ACTIVE,
    /** Завершённые и отменённые записи, последние первыми. */
    HISTORY
}
