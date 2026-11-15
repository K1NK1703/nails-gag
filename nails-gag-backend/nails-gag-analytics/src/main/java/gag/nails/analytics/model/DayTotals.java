package gag.nails.analytics.model;

import java.time.LocalDate;

/**
 * Итоги дня: суммы всех ключей метрик за день. День без событий — нули.
 *
 * @param day день в поясе салона
 * @param visitors уникальные посетители дня ({@link Metric#VISITORS})
 * @param pageViews просмотры страниц ({@link Metric#PAGE_VIEW}), все пути вместе
 * @param ctaClicks нажатия кнопки записи ({@link Metric#CTA_CLICK}), все места вместе
 * @param contactClicks переходы к контактам ({@link Metric#CONTACT_CLICK}), все каналы вместе
 */
public record DayTotals(LocalDate day, long visitors, long pageViews, long ctaClicks, long contactClicks) {}
