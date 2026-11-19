package gag.nails.notifications.internal.dispatch;

import org.jspecify.annotations.Nullable;

/**
 * Адрес получателя, определённый в момент отправки по текущим данным (спека §3.2): почта — из текущей карточки
 * клиентки (у сотрудника — из карточки его аккаунта), чат — из текущей привязки бота. В базе очереди адресов нет, в
 * логи они не попадают. {@link #toString()} адресов не показывает.
 *
 * @param email почта; пусто — письмо не отправить
 * @param chatId чат Telegram; пусто — бот не привязан
 * @param name имя получателя ({@code first_name} карточки) — для заголовка письма; пусто — карточки нет
 */
public record ResolvedRecipient(
        @Nullable String email,
        @Nullable Long chatId,
        @Nullable String name) {

    @Override
    public String toString() {
        return "ResolvedRecipient[email=" + (email == null ? "<none>" : "<set>") + ", chatId="
                + (chatId == null ? "<none>" : "<set>") + ", name=" + (name == null ? "<none>" : "<set>") + "]";
    }
}
