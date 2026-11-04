package gag.nails.finance.internal.recurring;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Настройки модуля нужны и без планировщика: тесты вызывают {@code FinanceMaintenance} сами. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FinanceProperties.class)
class FinanceMaintenanceConfiguration {}
