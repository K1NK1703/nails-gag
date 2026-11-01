package gag.nails.finance;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.common.paging.Paged;
import gag.nails.common.paging.Paging;
import gag.nails.common.time.ReportPeriod;
import gag.nails.finance.command.AddEntry;
import gag.nails.finance.command.UpdateEntry;
import gag.nails.finance.error.CategoryNotFoundException;
import gag.nails.finance.error.EntryNotFoundException;
import gag.nails.finance.model.EntryChangeView;
import gag.nails.finance.model.EntryFilter;
import gag.nails.finance.model.EntryKind;
import gag.nails.finance.model.EntryView;
import gag.nails.finance.model.QuickCombo;
import java.util.List;
import java.util.UUID;

/**
 * Ручные доходы и расходы. Вид записи — вид её категории. Каждое создание, фактическое изменение и удаление
 * записывается в журнал в той же транзакции (было → стало, аккаунт актора, момент); правка без изменений журнал не
 * пишет. Чтение — уровень чтения раздела «Финансы», изменения — уровень изменения ({@link AccessDeniedException}).
 *
 * <p>Пределы полей: сумма больше нуля, не больше {@code 9 999 999 999.99}, не больше двух знаков после запятой
 * ({@code amount}); день операции — в пределах {@link ReportPeriod#requireDay} ({@code occurredOn}); комментарий без
 * пробелов по краям — до 500 символов, пустой — комментария нет ({@code comment}). Нарушение —
 * {@link ValidationException} с этим полем.
 */
public interface FinanceEntries {

    /**
     * Новая запись вида своей категории.
     *
     * @throws CategoryNotFoundException если категории нет
     * @throws ValidationException (поле {@code categoryId}) категория скрыта; ({@code amount}, {@code occurredOn},
     *     {@code comment}) поле вне пределов
     */
    EntryView add(AddEntry command, Actor actor);

    /**
     * Правка записи. Категория — того же вида, что запись; скрытую категорию записи можно оставить, но нельзя выбрать
     * другую скрытую.
     *
     * @throws EntryNotFoundException если записи нет
     * @throws CategoryNotFoundException если новой категории нет
     * @throws ValidationException (поле {@code categoryId}) выбрана другая скрытая категория или категория другого
     *     вида; ({@code occurredOn}) запись повторяющегося шаблона переносится на день, где уже есть запись того же
     *     шаблона; остальные поля — как у {@link #add}
     */
    EntryView update(UUID entryId, UpdateEntry command, Actor actor);

    /**
     * Удаляет запись; её история остаётся.
     *
     * @throws EntryNotFoundException если записи нет
     */
    void delete(UUID entryId, Actor actor);

    /** @throws EntryNotFoundException если записи нет */
    EntryView get(UUID entryId, Actor actor);

    /**
     * Записи периода по дню операции (обе границы включительно), новые дни первыми; внутри дня — позже внесённые
     * первыми. Период — по правилам {@link ReportPeriod}.
     *
     * @throws ValidationException (поля {@code from}, {@code to}) период пуст или вне правил {@link ReportPeriod}
     */
    Paged<EntryView> list(EntryFilter filter, Paging paging, Actor actor);

    /**
     * До пяти последних различных пар «категория + сумма» ручных записей вида — по моменту внесения записи. Пары со
     * скрытыми категориями и записи, созданные повторяющимся шаблоном, не предлагаются.
     *
     * @throws ValidationException (поле {@code kind}) вид не задан
     */
    List<QuickCombo> recentCombos(EntryKind kind, Actor actor);

    /**
     * Журнал изменений записи от создания; у удалённой записи история остаётся.
     *
     * @throws EntryNotFoundException если такой записи не было никогда
     */
    List<EntryChangeView> history(UUID entryId, Actor actor);
}
