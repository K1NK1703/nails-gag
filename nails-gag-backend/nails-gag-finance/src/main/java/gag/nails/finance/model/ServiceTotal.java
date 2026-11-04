package gag.nails.finance.model;

import gag.nails.catalog.model.ServiceKind;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Услуга или дополнение в фактическом составе визитов периода.
 *
 * @param title название из записи самого позднего визита периода с этой услугой
 * @param count сколько раз оказана
 * @param priceSum сумма её цен по прайсу в записях визитов; скидка визита на услуги не делится
 */
public record ServiceTotal(UUID serviceId, ServiceKind kind, String title, int count, BigDecimal priceSum) {}
