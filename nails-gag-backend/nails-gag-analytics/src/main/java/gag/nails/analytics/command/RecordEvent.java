package gag.nails.analytics.command;

import org.jspecify.annotations.Nullable;

/**
 * Событие фронта как оно пришло: имя и поля без проверки, проверяет модуль. Виды событий и их поля:
 *
 * <ul>
 *   <li>{@code page_view} — {@code path} (обязательно), {@code entry} и {@code referrer} (необязательно);
 *   <li>{@code cta_click} — {@code place};
 *   <li>{@code contact_click} — {@code channel};
 *   <li>{@code gallery_open} — {@code index};
 *   <li>{@code faq_open} — {@code id};
 *   <li>{@code map_open} — без полей.
 * </ul>
 *
 * <p>Событие отбраковывается, если имя неизвестно, обязательного поля нет, значение неверное или заполнено поле,
 * которого у этого вида события нет (например, {@code place} у {@code page_view}): так отсекаются подделанные и
 * искажённые тела. Поля, которых нет в этой записи (например, {@code ts} фронта), отбрасывает HTTP-слой при разборе.
 *
 * @param name вид события
 * @param path путь страницы {@code page_view}, как {@code location.pathname}
 * @param place место кнопки записи {@code cta_click}
 * @param channel канал {@code contact_click}
 * @param index номер фото {@code gallery_open}
 * @param id идентификатор вопроса {@code faq_open}
 * @param entry {@code true} у первого {@code page_view} после загрузки сайта: только тогда считаются источник и
 *     устройство
 * @param referrer хост страницы, с которой пришли ({@code URL.hostname} из {@code document.referrer}), у входа на сайт
 */
public record RecordEvent(
        @Nullable String name,
        @Nullable String path,
        @Nullable String place,
        @Nullable String channel,
        @Nullable Integer index,
        @Nullable String id,
        @Nullable Boolean entry,
        @Nullable String referrer) {}
