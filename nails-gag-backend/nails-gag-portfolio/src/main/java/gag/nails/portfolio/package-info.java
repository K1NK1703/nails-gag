/**
 * Портфолио: работы мастера на лендинге и правило их публичности. Фото работы — файл модуля {@code files}; работа
 * клиента публикуется, только пока у клиента есть согласие на публикацию фото (копию согласий модуль ведёт сам по
 * событиям модуля клиентов). Публичный API — этот пакет (интерфейсы сервисов) и подпакеты {@code model},
 * {@code command}, {@code error}; всё остальное в {@code internal}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "portfolio")
package gag.nails.portfolio;
