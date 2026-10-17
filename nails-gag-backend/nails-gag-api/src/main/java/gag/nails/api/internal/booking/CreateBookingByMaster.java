package gag.nails.api.internal.booking;

import gag.nails.booking.command.CreateBooking;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Тело {@code POST /api/bookings/by-master}: поля команды {@link CreateBooking} и решение о предоплате. Ограничения
 * полей — те же, что у команды: тело становится ею.
 *
 * @param clientId карточка клиента, в том числе заблокированного
 * @param slotId слот; прошедший допустим в пределах срока исправления отметок — так вносится визит без записи
 * @param mainServiceId основная услуга
 * @param addonServiceIds дополнения без повторов, до 20; пустой список — без дополнений
 * @param comment пожелания клиента, до 500 символов; {@code null} — без комментария
 * @param awaitPrepayment ждёт ли запись предоплату; признак клиента при этом не меняется
 */
record CreateBookingByMaster(
        @NotNull UUID clientId,
        @NotNull UUID slotId,
        @NotNull UUID mainServiceId,
        @NotNull @Size(max = 20) List<@NotNull UUID> addonServiceIds,
        @Size(max = 500) @Nullable String comment,
        boolean awaitPrepayment) {

    /** Команда модуля из полей тела; {@code awaitPrepayment} передаётся модулю отдельно. */
    CreateBooking toCommand() {
        return new CreateBooking(clientId, slotId, mainServiceId, addonServiceIds, comment);
    }
}
