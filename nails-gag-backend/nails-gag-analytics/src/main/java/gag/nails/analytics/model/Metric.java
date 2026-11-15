package gag.nails.analytics.model;

/**
 * Что считается за день. В базе — имя в нижнем регистре ({@code page_view}, …). У каждой метрики свои ключи; новый
 * ключ сверх предела за день ({@code nails-gag.analytics.max-keys-per-metric}) засчитывается в {@code other}.
 * Туда же попадает любой непубличный путь просмотра страницы.
 */
public enum Metric {
    /** Просмотр страницы; ключ — публичный путь ({@code /}, {@code /terms}, {@code /privacy}, {@code /booking}). */
    PAGE_VIEW,
    /** Нажатие кнопки записи; ключ — место: {@code header}, {@code hero}, {@code price}. */
    CTA_CLICK,
    /** Переход к контакту; ключ — канал: {@code telegram}, {@code channel}. */
    CONTACT_CLICK,
    /** Открытие фото галереи; ключ — номер фото {@code 0}…{@code 99}. */
    GALLERY_OPEN,
    /** Раскрытие вопроса; ключ — идентификатор вопроса. */
    FAQ_OPEN,
    /** Открытие карты; ключ всегда {@code map}. */
    MAP_OPEN,
    /** Откуда пришли (только у входа на сайт); ключ — домен источника или {@code direct}. */
    SOURCE,
    /** Устройство (только у входа на сайт); ключ — {@code mobile}, {@code tablet} или {@code desktop}. */
    DEVICE,
    /** Уникальные посетители дня по суточному отпечатку; ключ всегда {@code all}. */
    VISITORS
}
