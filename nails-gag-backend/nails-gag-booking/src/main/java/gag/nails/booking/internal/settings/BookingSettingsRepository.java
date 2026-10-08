package gag.nails.booking.internal.settings;

import gag.nails.booking.internal.error.BookingRowMissingException;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingSettingsRepository extends JpaRepository<BookingSettingsEntity, UUID> {

    /** Единственная строка правил; её создаёт seed миграции. */
    default BookingSettingsEntity single() {
        return findAll().stream().findFirst().orElseThrow(() -> new BookingRowMissingException("booking.settings"));
    }
}
