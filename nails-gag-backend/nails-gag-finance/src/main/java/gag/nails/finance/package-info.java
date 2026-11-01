/**
 * Финансы мастера: категории, ручные доходы и расходы, журнал их изменений, быстрый ввод. Доход от визитов и
 * предоплат живёт в модуле записей, копии здесь нет. Права — раздел «Финансы»: чтение и изменение. Публичный API —
 * этот пакет (интерфейсы сервисов) и подпакеты {@code model}, {@code command}, {@code error}; всё остальное в
 * {@code internal}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "finance")
package gag.nails.finance;
