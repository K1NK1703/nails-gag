/**
 * Наблюдаемость запросов {@code /api/*} (спецификация API, §13): кто сделал запрос — {@code accountId} в MDC и
 * атрибут span-а запроса — и одна строка журнала на запрос с шаблоном адреса, статусом и временем.
 */
package gag.nails.api.internal.observability;
