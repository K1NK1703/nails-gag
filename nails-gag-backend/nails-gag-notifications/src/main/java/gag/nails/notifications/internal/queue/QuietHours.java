package gag.nails.notifications.internal.queue;

import gag.nails.notifications.internal.NotificationsProperties.QuietHoursWindow;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Тихие часы клиенток (спека §3.3): момент, попавший в окно тихих часов по времени салона, переносится на их конец.
 * Чистая функция без часов и базы; окно — из настроек ({@code nails-gag.notifications.quiet-hours}), пояс — салона.
 * Окно может переходить через полночь; равные границы — тихих часов нет.
 */
public final class QuietHours {

    private final QuietHoursWindow window;
    private final ZoneId zone;

    public QuietHours(QuietHoursWindow window, ZoneId zone) {
        this.window = window;
        this.zone = zone;
    }

    /**
     * Момент вне тихих часов — как есть; в тихих часах — ближайший их конец (граница {@code to} не входит в окно).
     * Конец окна через полночь, если момент ещё до полуночи, — на следующий день салона, иначе — в тот же.
     */
    public Instant shift(Instant at) {
        var local = at.atZone(zone);
        var time = local.toLocalTime();
        if (!window.contains(time)) {
            return at;
        }
        var endDay = time.isBefore(window.to())
                ? local.toLocalDate()
                : local.toLocalDate().plusDays(1);
        return endDay.atTime(window.to()).atZone(zone).toInstant();
    }
}
