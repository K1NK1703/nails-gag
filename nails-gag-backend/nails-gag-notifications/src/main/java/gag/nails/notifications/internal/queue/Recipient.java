package gag.nails.notifications.internal.queue;

import java.util.UUID;

/**
 * Кому адресовано сообщение (спека §3.2): клиентке — её карточке (у карточки, заведённой мастером, аккаунта может не
 * быть), сотруднику — его аккаунту. Каналы и адреса определяются при отправке, не здесь.
 */
public sealed interface Recipient {

    /** Часть ключа повтора: {@code client:<id>} или {@code account:<id>}. */
    String key();

    /** Карточка клиента: клиентские виды сообщений. */
    record Client(UUID clientId) implements Recipient {

        @Override
        public String key() {
            return "client:" + clientId;
        }
    }

    /** Аккаунт сотрудника: мастерские виды ({@code STAFF_*}). */
    record Account(UUID accountId) implements Recipient {

        @Override
        public String key() {
            return "account:" + accountId;
        }
    }
}
