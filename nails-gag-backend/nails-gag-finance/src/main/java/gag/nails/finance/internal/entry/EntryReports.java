package gag.nails.finance.internal.entry;

import gag.nails.finance.model.EntryKind;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ручные записи для отчётов: суммы считает база (агрегаты SQL), каждый метод — один запрос. Период — уже проверенный
 * вызывающим, обе границы включительно. Только внутри транзакции отчёта ({@code MANDATORY}): отчёт читает и записи, и
 * факты модуля записей в одной транзакции — с одним снимком базы.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class EntryReports {

    private final EntryRepository entries;

    EntryReports(EntryRepository entries) {
        this.entries = entries;
    }

    /** Суммы по категориям с записями в периоде: по виду, внутри вида — в порядке категорий в списке вида. */
    public List<CategorySum> sumByCategory(LocalDate from, LocalDate to) {
        return entries.sumByCategory(from, to);
    }

    /** Суммы по месяцу дня операции и виду; месяцев и видов без записей нет. */
    public List<MonthSum> sumByMonth(LocalDate from, LocalDate to) {
        return entries.sumByMonth(from, to);
    }

    /**
     * Записи периода видов {@code kinds} (пусто — ни одной) и, если задана, одной категории: новые дни первыми,
     * внутри дня — позже внесённые первыми, при равенстве момента — по убыванию id.
     */
    public List<EntryLine> lines(LocalDate from, LocalDate to, Set<EntryKind> kinds, @Nullable UUID categoryId) {
        if (kinds.isEmpty()) {
            return List.of();
        }
        return entries.findLines(from, to, kinds, categoryId);
    }
}
