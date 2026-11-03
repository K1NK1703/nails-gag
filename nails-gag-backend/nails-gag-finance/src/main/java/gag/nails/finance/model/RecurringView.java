package gag.nails.finance.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Шаблон повторяющейся ежемесячной записи.
 *
 * @param kind вид — всегда вид категории
 * @param categoryName текущее название категории
 * @param dayOfMonth число месяца, 1–31; в коротком месяце — его последний день
 * @param startsOn первый день, с которого шаблон создаёт записи
 * @param endsOn последний день, по который шаблон создаёт записи; пусто — без конца
 * @param paused на паузе: записи не создаются, пропущенное потом не догоняется
 */
public record RecurringView(
        UUID id,
        EntryKind kind,
        UUID categoryId,
        String categoryName,
        BigDecimal amount,
        @Nullable String comment,
        int dayOfMonth,
        LocalDate startsOn,
        @Nullable LocalDate endsOn,
        boolean paused) {}
