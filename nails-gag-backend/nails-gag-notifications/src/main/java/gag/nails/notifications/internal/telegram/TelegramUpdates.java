package gag.nails.notifications.internal.telegram;

import gag.nails.notifications.internal.NotificationsProperties;
import gag.nails.notifications.internal.preference.AccountChannels;
import gag.nails.notifications.internal.preference.LinkTokens;
import gag.nails.notifications.internal.telegram.TelegramApi.Update;
import java.time.Clock;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Сообщения боту (спека §3.6). Только личные чаты с людьми: группы, каналы и боты не получают ответа и ничего не
 * меняют.
 *
 * <ul>
 *   <li>{@code /start <токен>} — токен ссылки из кабинета гасится первым ({@link LinkTokens#consume}), затем чат
 *       привязывается к аккаунту ссылки (прежний аккаунт этого чата и прежний чат аккаунта отвязываются) — одна
 *       транзакция в порядке блокировок модуля «токен → чат». Токен неверный, погашенный, заменённый или истёкший —
 *       «ссылка устарела», ничего не меняется;
 *   <li>{@code /start} без токена — как подключить бота;
 *   <li>{@code /stop} — чат отвязывается;
 *   <li>остальное (текст, стикеры, другие команды) — подсказка с Telegram мастера.
 * </ul>
 *
 * Команда может быть с именем бота ({@code /start@бот токен}) — так её шлют из меню команд; с именем другого бота это
 * просто текст. Повтор того же обновления (после рестарта — смещение приёма в памяти) безопасен: токен одноразовый,
 * привязка остаётся, повтор получает «ссылка устарела».
 *
 * <p>Ответ уходит после фиксации изменений, с коротким таймаутом ({@link TelegramApi#reply}); не ушёл — в лог (метод
 * и код ответа, без чата и текста), привязка остаётся, приём обновлений идёт дальше. Сбой базы — исключением: приём
 * повторит обновление. В логе привязки нет ни чата, ни аккаунта (спека §6).
 */
@Component
class TelegramUpdates {

    static final String LINKED = "Готово: уведомления nails.gag будут приходить сюда.";
    static final String EXPIRED = "Ссылка устарела, получите новую в личном кабинете.";
    static final String STOPPED = "Уведомления отключены. Подключить снова можно в личном кабинете.";

    private static final String PRIVATE = "private";
    private static final String START = "/start";
    private static final String STOP = "/stop";

    private static final Logger log = LoggerFactory.getLogger(TelegramUpdates.class);

    private final TelegramApi api;
    private final LinkTokens linkTokens;
    private final AccountChannels accountChannels;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final String botUsername;
    private final String help;
    private final String hint;

    TelegramUpdates(
            TelegramApi api,
            LinkTokens linkTokens,
            AccountChannels accountChannels,
            TransactionTemplate transaction,
            Clock clock,
            NotificationsProperties properties) {
        this.api = api;
        this.linkTokens = linkTokens;
        this.accountChannels = accountChannels;
        this.transaction = transaction;
        this.clock = clock;
        this.botUsername = properties.telegram().botUsername();
        this.help = "Чтобы получать сюда уведомления о записи, подключите бота в личном кабинете: "
                + properties.siteUrl() + "/account";
        this.hint = "Я только присылаю уведомления о записи. Написать мастеру: " + properties.masterTelegram() + ".";
    }

    /** Обрабатывает одно обновление и отвечает в чат. */
    void handle(Update update) {
        var message = update.message();
        if (message == null) {
            return;
        }
        var chat = message.chat();
        var from = message.from();
        if (chat == null || !PRIVATE.equals(chat.type()) || from == null || from.isBot()) {
            return;
        }
        var chatId = chat.id();
        var reply = switch (Command.parse(message.text(), botUsername)) {
            case Command.Start start -> {
                var token = start.token();
                yield token == null ? help : link(token, chatId) ? LINKED : EXPIRED;
            }
            case Command.Stop stop -> {
                accountChannels.unlinkChat(chatId);
                yield STOPPED;
            }
            case Command.Other other -> hint;
        };
        reply(chatId, reply, update.updateId());
    }

    /** Гасит токен и привязывает чат к его аккаунту — одной транзакцией; {@code false} — токен не годится. */
    private boolean link(String token, long chatId) {
        var linked = transaction.execute(status -> linkTokens
                .consume(token)
                .map(accountId -> {
                    accountChannels.bindTelegram(accountId, chatId, clock.instant());
                    log.info("telegram chat linked by a link token");
                    return true;
                })
                .orElse(false));
        return Boolean.TRUE.equals(linked);
    }

    private void reply(long chatId, String text, long updateId) {
        try {
            api.reply(chatId, text);
        } catch (TelegramApiException e) {
            log.warn("telegram reply not sent: {} (update {})", e.getMessage(), updateId);
        }
    }

    /** Что человек написал боту. */
    sealed interface Command {

        /** {@code /start} с токеном ссылки или без. */
        record Start(@Nullable String token) implements Command {

            @Override
            public String toString() {
                return "Start[token=" + (token == null ? "<none>" : "<hidden>") + "]";
            }
        }

        /** {@code /stop}. */
        record Stop() implements Command {}

        /** Всё остальное. */
        record Other() implements Command {}

        /**
         * Команда из текста: первое слово, у которого может быть {@code @имя_бота} (без учёта регистра), и аргумент
         * через пробел.
         */
        static Command parse(@Nullable String text, String botUsername) {
            if (text == null || text.isBlank()) {
                return new Other();
            }
            var parts = text.strip().split("\\s+", 2);
            var command = parts[0];
            var at = command.indexOf('@');
            if (at >= 0) {
                if (!command.substring(at + 1).equalsIgnoreCase(botUsername)) {
                    return new Other();
                }
                command = command.substring(0, at);
            }
            var argument = parts.length > 1 ? parts[1].strip() : "";
            return switch (command.toLowerCase(Locale.ROOT)) {
                case START -> new Start(argument.isEmpty() ? null : argument);
                case STOP -> new Stop();
                default -> new Other();
            };
        }
    }
}
