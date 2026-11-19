package gag.nails.notifications.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Настройки модуля. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationsProperties.class)
class NotificationsConfiguration {}
