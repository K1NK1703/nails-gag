package gag.nails.notifications.model;

import java.net.URI;
import java.time.Instant;

/**
 * Одноразовая ссылка привязки Telegram-бота.
 *
 * @param url {@code https://t.me/<бот>?start=<токен>}
 * @param expiresAt после этого момента ссылка не привязывает
 */
public record TelegramLink(URI url, Instant expiresAt) {}
