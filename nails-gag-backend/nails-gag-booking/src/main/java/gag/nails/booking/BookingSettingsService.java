package gag.nails.booking;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import java.util.List;

/**
 * Правила записи. Чтение открыто: значения показываются клиенту в текстах и подсказках. Изменение
 * требует уровня изменения раздела «Правила записи», журнал — уровня чтения. Новые значения действуют
 * на будущие проверки; уже рассчитанные сроки предоплаты не пересчитываются.
 */
public interface BookingSettingsService {

    /** Текущие правила. */
    BookingSettingsView settings();

    /**
     * Задаёт все правила сразу. В журнал попадает каждое изменившееся поле; неизменившиеся — нет.
     * Изменение окна бесплатной отмены, процента предоплаты и допустимого опоздания — новая редакция
     * оферты: её версию мастер добавляет отдельно ({@code DocumentVersionService} в {@code clients}).
     *
     * @throws ValidationException если значение вне допустимого диапазона
     * @throws AccessDeniedException если у актора нет изменения раздела «Правила записи»
     */
    BookingSettingsView update(UpdateBookingSettings command, Actor actor);

    /**
     * Журнал изменений правил, новые первыми. Нужен уровень чтения раздела «Правила записи».
     *
     * @throws AccessDeniedException если у актора нет чтения раздела «Правила записи»
     */
    List<BookingSettingsChangeView> history(Actor actor);
}
