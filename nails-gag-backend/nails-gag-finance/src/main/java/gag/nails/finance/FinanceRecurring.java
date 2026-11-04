package gag.nails.finance;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.common.time.ReportPeriod;
import gag.nails.finance.command.CreateRecurring;
import gag.nails.finance.command.UpdateRecurring;
import gag.nails.finance.error.CategoryNotFoundException;
import gag.nails.finance.error.RecurringNotFoundException;
import gag.nails.finance.model.RecurringView;
import java.util.List;
import java.util.UUID;

/**
 * Шаблоны повторяющихся ежемесячных записей. Ночная задача ({@link FinanceMaintenance}) создаёт по шаблону запись в
 * его число каждого месяца (в коротком месяце — в последний день), с {@code startsOn} по {@code endsOn}, не на паузе.
 * Созданная запись — обычная ручная запись с отметкой шаблона: правится, переносится и удаляется, а за её исходный
 * день шаблон ничего не создаёт заново. У шаблона — не больше одной записи за день.
 *
 * <p>Прогресс шаблона — день, по который его записи уже созданы. Задача создаёт записи только за дни после него и
 * сдвигает его до сегодняшнего и на паузе, поэтому пропущенное на паузе и до продления {@code endsOn} не догоняется.
 * Создание и правка шаблона создают записи за наступившие дни сразу, не дожидаясь ночной задачи; новый шаблон с
 * {@code startsOn} в прошлом сразу получает записи и за прошедшие месяцы.
 *
 * <p>Категория шаблона, скрытая позже, записи не останавливает: шаблон уже выбран. Чтение — уровень чтения раздела
 * «Финансы», изменения — уровень изменения ({@link AccessDeniedException}). Пределы полей: сумма и комментарий —
 * как у {@link FinanceEntries}; число месяца 1–31 ({@code dayOfMonth}); {@code startsOn} и {@code endsOn} — в
 * пределах {@link ReportPeriod#requireDay}, {@code endsOn} не раньше {@code startsOn}. Нарушение —
 * {@link ValidationException} с этим полем.
 */
public interface FinanceRecurring {

    /**
     * Все шаблоны по числу месяца.
     *
     * @throws AccessDeniedException если у актора нет чтения «Финансов»
     */
    List<RecurringView> list(Actor actor);

    /**
     * Новый шаблон вида своей категории, не на паузе. Записи за уже наступившие дни (с {@code startsOn} по сегодня)
     * создаются сразу.
     *
     * @throws CategoryNotFoundException если категории нет
     * @throws ValidationException (поле {@code categoryId}) категория скрыта; остальные поля — вне пределов
     */
    RecurringView create(CreateRecurring command, Actor actor);

    /**
     * Правка шаблона; действует только на будущие записи. Сначала шаблон создаёт по прежним полям записи за уже
     * наступившие дни, если ночная задача их ещё не создала. Затем применяются новые поля, и записи, которые по ним
     * уже наступили, создаются сразу. Правила:
     *
     * <ul>
     *   <li>перенос {@code startsOn} назад и продление {@code endsOn} прошедшие дни не догоняют;
     *   <li>смена числа месяца не даёт второй записи и не пропускает месяц: если запись текущего месяца уже создана,
     *       следующая — в новое число следующего месяца; если ещё нет — в новое число текущего месяца (если оно уже
     *       прошло — запись этим числом создаётся сразу). Есть ли запись месяца, решают прежние поля: если по прежним
     *       {@code startsOn}/{@code endsOn} день текущего месяца не входил в срок шаблона, записи месяца нет, и при
     *       одновременном переносе {@code startsOn} назад или продлении {@code endsOn} месяц не пропускается —
     *       запись за новое число этого месяца создаётся.
     * </ul>
     *
     * @throws RecurringNotFoundException если шаблона нет
     * @throws CategoryNotFoundException если новой категории нет
     * @throws ValidationException (поле {@code categoryId}) выбрана другая скрытая категория или категория другого
     *     вида; остальные поля — вне пределов
     */
    RecurringView update(UUID recurringId, UpdateRecurring command, Actor actor);

    /**
     * Ставит шаблон на паузу; уже на паузе — без изменений. Записи за наступившие до паузы дни создаются сразу, если
     * задача их ещё не создала; дни на паузе потом не догоняются.
     *
     * @throws RecurringNotFoundException если шаблона нет
     */
    void pause(UUID recurringId, Actor actor);

    /**
     * Снимает паузу; не на паузе — без изменений. Дни паузы не догоняются; запись в сегодняшний день создаётся, если
     * задача ещё не прошла его на паузе.
     *
     * @throws RecurringNotFoundException если шаблона нет
     */
    void resume(UUID recurringId, Actor actor);

    /**
     * Удаляет шаблон; созданные им записи остаются и по-прежнему считаются созданными шаблоном.
     *
     * @throws RecurringNotFoundException если шаблона нет
     */
    void delete(UUID recurringId, Actor actor);
}
