package gag.nails.booking.internal.maintenance;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Настройки модуля нужны и без планировщика: тесты вызывают {@code BookingMaintenance} сами. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BookingProperties.class)
class BookingMaintenanceConfiguration {}
