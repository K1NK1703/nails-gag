package gag.nails.finance.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Сумма ручных записей категории за период.
 *
 * @param name текущее название категории
 * @param hidden категория скрыта — её записи всё равно считаются
 * @param amount сумма записей категории, больше нуля: категорий без записей в периоде в отчёте нет
 */
public record CategoryTotal(UUID categoryId, String name, boolean hidden, BigDecimal amount) {}
