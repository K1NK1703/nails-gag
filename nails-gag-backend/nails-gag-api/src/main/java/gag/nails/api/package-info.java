/**
 * HTTP-слой: адреса {@code /api/**} поверх публичных API доменных модулей, защита (сессия, CSRF, лимиты,
 * заголовки), единый формат ошибок и JSON. Публичного Java-API нет: этот пакет содержит только описание
 * модуля, всё остальное в {@code internal}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "api")
package gag.nails.api;
