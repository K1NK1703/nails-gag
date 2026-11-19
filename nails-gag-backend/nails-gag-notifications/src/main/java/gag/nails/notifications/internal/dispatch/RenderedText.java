package gag.nails.notifications.internal.dispatch;

import gag.nails.common.error.InternalException;

/**
 * Текст сообщения для канала: простой текст без разметки.
 *
 * @param subject первая строка текста — тема письма
 * @param body весь текст, включая первую строку: так он уходит в Telegram и в тело письма
 */
public record RenderedText(String subject, String body) {

    public RenderedText {
        if (subject.isBlank() || !body.startsWith(subject)) {
            throw new InternalException("message subject must be the first line of its body");
        }
    }

    /** Текст шаблона: тема — его первая строка. */
    public static RenderedText of(String text) {
        var end = text.indexOf('\n');
        return new RenderedText(end < 0 ? text : text.substring(0, end), text);
    }
}
