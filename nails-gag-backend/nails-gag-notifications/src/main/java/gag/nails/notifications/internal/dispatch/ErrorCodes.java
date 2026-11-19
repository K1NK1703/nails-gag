package gag.nails.notifications.internal.dispatch;

import gag.nails.common.error.InternalException;
import java.util.regex.Pattern;

/**
 * Коды ошибок доставки, которые пишет сама отправка (спека §3.5), и проверка формы любого кода — та же, что у
 * колонки {@code delivery.error_code}: ошибка в коде канала видна сразу, а не нарушением ограничения базы при записи
 * результата.
 */
final class ErrorCodes {

    /** Сообщение истекло ({@code expires_at}), не доставившись. */
    static final String EXPIRED = "expired";

    /** Пять попыток — пять временных сбоев. */
    static final String TRANSIENT_EXHAUSTED = "transient_exhausted";

    /**
     * Канал стал недоступен получателю после создания доставки (спека §3.2: выключен в настройках, нет адреса — почта
     * удалена или карточка обезличена, бот отвязан — или канал не настроен на сервере).
     */
    static final String UNAVAILABLE = "unavailable";

    /**
     * Сообщение стало неактуальным после создания доставки (спека §5.2: запись отменена или перенесена, предложение
     * закрыто) или записи больше нет: повтор не отправляется.
     */
    static final String OUTDATED = "outdated";

    /** Канал бросил исключение вместо результата — считается временным сбоем. */
    static final String CHANNEL_ERROR = "channel_error";

    private static final Pattern FORM = Pattern.compile("[a-z][a-z0-9_]{0,31}");

    private ErrorCodes() {}

    static void require(String errorCode) {
        if (!FORM.matcher(errorCode).matches()) {
            throw new InternalException("delivery error code has an invalid form: " + errorCode);
        }
    }
}
