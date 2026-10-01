package gag.nails.testsupport;

import gag.nails.common.time.SalonTimeZone;
import java.time.Clock;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Подменяет {@link Clock} в тестах модулей: 1 октября 2026, 06:00 UTC (09:00 в поясе салона из профиля test). */
@TestConfiguration(proxyBeanMethods = false)
public class TestTimeConfiguration {

    public static final Instant START = Instant.parse("2026-10-01T06:00:00Z");

    @Bean
    @Primary
    MutableClock mutableClock(SalonTimeZone salonTimeZone) {
        return new MutableClock(START, salonTimeZone.zone());
    }
}
