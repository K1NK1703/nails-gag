package gag.nails.api.internal.http;

/**
 * Какие записи клиента показать ({@code GET /api/clients/{id}/bookings?scope=…}). В адресе — в нижнем регистре
 * ({@code active}, {@code history}) через конвертер {@link WebConfiguration}; лежит рядом с ним, чтобы слой адресов не
 * зависел от областей. Публичное: его принимает контроллер клиентов.
 */
public enum BookingScope {
    /** Активные записи, ближайшие первыми. */
    ACTIVE,
    /** Завершённые и отменённые записи, последние первыми. */
    HISTORY
}
