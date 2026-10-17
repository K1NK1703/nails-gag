package gag.nails.api.internal.booking;

import gag.nails.api.ApiTestSupport;
import gag.nails.booking.BookingService;
import gag.nails.booking.BookingSettingsService;
import gag.nails.booking.command.CreateBooking;
import gag.nails.booking.model.BookingView;
import gag.nails.clients.ClientQueries;
import gag.nails.clients.command.RecordConsent;
import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentSource;
import gag.nails.clients.model.DocumentKind;
import gag.nails.schedule.ScheduleService;
import gag.nails.schedule.model.SlotView;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient.RequestHeadersSpec;

/**
 * Обвязка HTTP-тестов записей: записи, слоты и клиенты для подготовки данных. Слоты — на днях {@link #freshDay()}, в
 * полдень: другие тесты их не трогают. Тесты со сдвигом часов входят после сдвига: сессия, открытая раньше, к
 * сдвинутому моменту могла истечь.
 */
abstract class BookingApiTestSupport extends ApiTestSupport {

    static final LocalTime NOON = LocalTime.of(12, 0);

    @Autowired
    BookingService bookings;

    @Autowired
    BookingSettingsService bookingSettings;

    @Autowired
    ScheduleService schedule;

    @Autowired
    ClientQueries clientQueries;

    /** Новый клиент с согласиями на действующие политику и оферту: без них записаться сам он не может. */
    Session customerWithConsents() {
        var customer = signInClient();
        var offer = documents.current(DocumentKind.OFFER).version();
        clientService.recordConsent(
                actor(customer).requireClientId(),
                new RecordConsent(ConsentKind.OFFER, offer, ConsentSource.SITE_CHECKBOX),
                actor(customer));
        return customer;
    }

    /** Подтверждённая или ждущая предоплату запись клиента на свой день в полдень; её заводит разработчик. */
    BookingView bookFor(UUID clientId, boolean awaitPrepayment) {
        return bookings.createByMaster(
                new CreateBooking(clientId, freeSlot().id(), mainService().id(), List.of(), null),
                awaitPrepayment,
                developerActor());
    }

    /** Свободный слот в полдень дня, которого нет у других тестов. */
    SlotView freeSlot() {
        return schedule.addSlot(freshDay(), NOON, developerActor());
    }

    /**
     * Тело {@code POST /api/bookings/by-master} без дополнений и комментария; изменяемое — тест может убрать поле или
     * задать комментарий.
     */
    static Map<String, @Nullable Object> byMasterBody(
            UUID clientId, UUID slotId, UUID mainServiceId, boolean awaitPrepayment) {
        var body = new HashMap<String, @Nullable Object>();
        body.put("clientId", clientId);
        body.put("slotId", slotId);
        body.put("mainServiceId", mainServiceId);
        body.put("addonServiceIds", List.of());
        body.put("comment", null);
        body.put("awaitPrepayment", awaitPrepayment);
        return body;
    }

    /** Переход записи без тела: {@code POST /api/bookings/{id}/<действие>}. */
    RequestHeadersSpec<?> bookingAction(Session session, UUID bookingId, String action) {
        return as(session, csrf(client.post().uri("/api/bookings/{id}/" + action, bookingId)));
    }
}
