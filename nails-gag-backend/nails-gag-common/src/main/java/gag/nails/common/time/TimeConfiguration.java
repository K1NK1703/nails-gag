package gag.nails.common.time;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Единственный источник «сейчас»: в коде только {@code Clock}, в тестах он подменяется. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TimeProperties.class)
class TimeConfiguration {

    @Bean
    Clock clock(TimeProperties properties) {
        return Clock.system(properties.timeZone());
    }

    @Bean
    SalonTimeZone salonTimeZone(TimeProperties properties) {
        return new SalonTimeZone(properties.timeZone());
    }
}
