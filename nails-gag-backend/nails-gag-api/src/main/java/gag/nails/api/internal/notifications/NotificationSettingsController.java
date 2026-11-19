package gag.nails.api.internal.notifications;

import gag.nails.api.internal.http.PageDefaults;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.ValidationException;
import gag.nails.common.paging.Paged;
import gag.nails.common.paging.Paging;
import gag.nails.notifications.NotificationSettings;
import gag.nails.notifications.command.DeliveryFilter;
import gag.nails.notifications.model.DeliveryStatus;
import gag.nails.notifications.model.DeliveryView;
import gag.nails.notifications.model.ReminderSettingsChange;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Настройки напоминаний о визите и журналы уведомлений (спецификация уведомлений, §3.4, §3.12, §4). Чтение — по уровню
 * чтения раздела «Уведомления», изменение — по уровню изменения; права проверяет модуль: сотруднику без них —
 * {@code 403 common.access_denied}, клиенту — {@code 404 common.not_found}, без сессии — {@code 401}. Ответы —
 * {@code Cache-Control: no-store}.
 */
@RestController
@RequestMapping("/api/notifications")
class NotificationSettingsController {

    private final NotificationSettings settings;

    NotificationSettingsController(NotificationSettings settings) {
        this.settings = settings;
    }

    /** Текущие настройки напоминаний; время накануне — {@code "ЧЧ:ММ"} по времени салона. */
    @GetMapping("/settings")
    VisitReminders settings(Actor actor) {
        return VisitReminders.of(settings.settings(actor));
    }

    /**
     * Обе настройки сразу; {@code null} выключает напоминание. Каждое изменившееся поле пишется в журнал,
     * неотправленные напоминания предстоящих записей рассчитываются заново. Время накануне не в целых минутах или в
     * тихих часах, часы вне 1..24 — {@code 400 common.validation} с полем тела ({@code reminderDayBeforeAt},
     * {@code reminderHoursBefore}).
     */
    @PutMapping("/settings")
    VisitReminders update(@Valid @RequestBody ChangeVisitReminders body, Actor actor) {
        try {
            return VisitReminders.of(settings.update(body.toCommand(), actor));
        } catch (ValidationException exception) {
            throw ChangeVisitReminders.inBodyTerms(exception);
        }
    }

    /**
     * Журнал изменений настроек напоминаний, новые первыми: поле — имя колонки ({@code reminder_day_before_at},
     * {@code reminder_hours_before}), значения текстом, {@code null} — напоминание было или стало выключено.
     */
    @GetMapping("/settings/history")
    List<ReminderSettingsChange> history(Actor actor) {
        return settings.history(actor);
    }

    /**
     * Журнал доставок постранично (спецификация API, §4.4): новые по времени отправки сообщения первыми; если заданы,
     * только в состоянии {@code status} ({@code pending}, {@code sent}, {@code failed}) и по записи {@code bookingId}.
     * Получатель — карточка клиента ({@code clientId}) или аккаунт сотрудника ({@code accountId}); адресов и текстов
     * нет.
     */
    @GetMapping("/deliveries")
    Paged<DeliveryView> deliveries(
            @RequestParam(required = false) @Nullable DeliveryStatus status,
            @RequestParam(required = false) @Nullable UUID bookingId,
            @RequestParam(defaultValue = PageDefaults.FIRST_PAGE) int page,
            @RequestParam(defaultValue = PageDefaults.SIZE) int size,
            Actor actor) {
        return settings.deliveries(new DeliveryFilter(status, bookingId), new Paging(page, size), actor);
    }
}
