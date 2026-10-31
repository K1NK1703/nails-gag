package gag.nails.booking;

import gag.nails.booking.error.BookingNotFoundException;
import gag.nails.booking.error.CorrectionWindowClosedException;
import gag.nails.booking.error.InvalidTransitionException;
import gag.nails.booking.model.BookingView;
import gag.nails.booking.model.PrepaymentOutcome;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Предоплата по записи. Сайт денег не принимает и реквизитов не показывает: предоплата вносится вне
 * сайта, сотрудник отмечает её получение и сумму. Все операции требуют уровня изменения раздела «Записи»;
 * получение и подтверждение без предоплаты применимы только к записи, ждущей предоплату.
 *
 * <p>Исход полученной предоплаты ({@link PrepaymentOutcome}, в представлении записи) вычисляется по тому, чем
 * закончилась запись: активная или выполненная — в счёт визита; неявка, поздняя отмена клиентом, отказ из-за
 * опоздания и отмена записи клиента, удалившего аккаунт, — удержана; отмена в окне и мастером — возвращена.
 */
public interface BookingPrepayments {

    /**
     * Предоплата получена: запись подтверждена. Сумма — по умолчанию: процент предоплаты из правил записи от
     * прайса выбранного при записи состава, округлённый до рубля (половина — вверх), но не меньше рубля. Признак
     * обязательной предоплаты у клиента снимется после выполненного визита.
     *
     * @throws BookingNotFoundException если записи нет
     * @throws InvalidTransitionException если запись не ждёт предоплату
     * @throws AccessDeniedException если у актора нет изменения раздела «Записи»
     */
    BookingView markReceived(UUID bookingId, Actor actor);

    /**
     * Как {@link #markReceived(UUID, Actor)}, но с суммой, которую ввёл сотрудник.
     *
     * @param amount сумма предоплаты: больше нуля, не больше двух знаков после запятой и десяти до неё
     * @throws BookingNotFoundException если записи нет
     * @throws InvalidTransitionException если запись не ждёт предоплату
     * @throws ValidationException если сумма вне допустимого ({@code amount})
     * @throws AccessDeniedException если у актора нет изменения раздела «Записи»
     */
    BookingView markReceived(UUID bookingId, BigDecimal amount, Actor actor);

    /**
     * Подтверждает запись без предоплаты и снимает с клиента признак обязательной предоплаты. Признак
     * меняется от имени сотрудника, поэтому кроме изменения «Записей» нужен уровень изменения раздела
     * «Клиенты»; оба проверяются до изменений.
     *
     * @throws BookingNotFoundException если записи нет
     * @throws InvalidTransitionException если запись не ждёт предоплату
     * @throws AccessDeniedException если у актора нет изменения «Записей» или «Клиентов»
     */
    BookingView confirmWithoutPrepayment(UUID bookingId, Actor actor);

    /**
     * Переопределяет исход полученной предоплаты отменённой записи или неявки: удержана или возвращена. Значение,
     * совпадающее с правилом, снимает переопределение. Менять можно в пределах срока исправления отметок, считая
     * от отмены или неявки; исправление неявки на «выполнена» переопределение снимает.
     *
     * @param outcome {@link PrepaymentOutcome#KEPT} или {@link PrepaymentOutcome#RETURNED}
     * @throws BookingNotFoundException если записи нет
     * @throws InvalidTransitionException если предоплата не получена или запись не отменена и не отмечена неявкой
     * @throws CorrectionWindowClosedException если срок исправления истёк
     * @throws ValidationException если передан {@link PrepaymentOutcome#APPLIED} ({@code outcome})
     * @throws AccessDeniedException если у актора нет изменения раздела «Записи»
     */
    BookingView setOutcome(UUID bookingId, PrepaymentOutcome outcome, Actor actor);
}
