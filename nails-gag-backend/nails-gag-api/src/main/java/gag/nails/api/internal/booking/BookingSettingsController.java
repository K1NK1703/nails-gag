package gag.nails.api.internal.booking;

import gag.nails.booking.BookingSettingsService;
import gag.nails.booking.command.UpdateBookingSettings;
import gag.nails.booking.model.BookingSettingsChangeView;
import gag.nails.booking.model.BookingSettingsView;
import gag.nails.common.actor.Actor;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Правила записи (спецификация API, §7.6): текущие — любому вошедшему, клиенту нужны окно отмены и срок предоплаты;
 * изменение — по уровню изменения раздела «Правила записи», журнал — по уровню чтения. Права проверяет модуль.
 */
@RestController
@RequestMapping("/api/booking-settings")
class BookingSettingsController {

    private final BookingSettingsService settings;

    BookingSettingsController(BookingSettingsService settings) {
        this.settings = settings;
    }

    /**
     * Текущие правила. Модулю актор не нужен: параметр {@code actor} лишь требует сессию — без неё {@code 401}.
     */
    @GetMapping
    BookingSettingsView settings(Actor actor) {
        return settings.settings();
    }

    /**
     * Все правила сразу; новые значения действуют на будущие проверки. В журнал попадает каждое изменившееся поле.
     */
    @PutMapping
    BookingSettingsView update(@Valid @RequestBody UpdateBookingSettings body, Actor actor) {
        return settings.update(body, actor);
    }

    /** Журнал изменений правил, новые первыми. */
    @GetMapping("/history")
    List<BookingSettingsChangeView> history(Actor actor) {
        return settings.history(actor);
    }
}
