package gag.nails.notifications.internal.queue;

import gag.nails.common.time.SalonTimeZone;
import gag.nails.notifications.internal.NotificationsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Очередь сообщений. */
@Configuration(proxyBeanMethods = false)
class QueueConfiguration {

    /** Тихие часы клиенток из настроек — по времени салона. */
    @Bean
    QuietHours quietHours(NotificationsProperties properties, SalonTimeZone salonTimeZone) {
        return new QuietHours(properties.quietHours(), salonTimeZone.zone());
    }
}
