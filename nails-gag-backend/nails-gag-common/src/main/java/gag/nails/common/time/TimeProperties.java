package gag.nails.common.time;

import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code nails-gag.time-zone} — пояс салона, по умолчанию Europe/Moscow. */
@ConfigurationProperties("nails-gag")
record TimeProperties(ZoneId timeZone) {

    TimeProperties {
        if (timeZone == null) {
            timeZone = ZoneId.of("Europe/Moscow");
        }
    }
}
