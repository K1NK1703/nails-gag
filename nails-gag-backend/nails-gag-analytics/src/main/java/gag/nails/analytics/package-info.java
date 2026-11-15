/**
 * Счётчик посещений сайта: приём обезличенных событий фронта и дневные счётчики — события по ключам, уникальные
 * посетители за день по суточному отпечатку, источник перехода и тип устройства. Сырые события, IP и
 * {@code User-Agent} не хранятся. Публичный API — этот пакет (интерфейсы сервисов) и подпакеты {@code model},
 * {@code command}; всё остальное в {@code internal}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "analytics")
package gag.nails.analytics;
