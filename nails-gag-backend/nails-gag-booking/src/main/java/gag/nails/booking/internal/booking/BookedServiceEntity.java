package gag.nails.booking.internal.booking;

import gag.nails.booking.model.BookedServiceView;
import gag.nails.catalog.model.ServiceKind;
import gag.nails.catalog.model.ServiceView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(schema = "booking", name = "booking_service")
class BookedServiceEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id")
    private BookingEntity booking;

    private UUID serviceId;

    @Enumerated(EnumType.STRING)
    private ServiceKind kind;

    private String title;

    @Column(precision = 12, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    private ServicePhase phase;

    private int sortOrder;

    protected BookedServiceEntity() {}

    /** Снимок услуги каталога: дальнейшие правки прайса запись не меняют. */
    BookedServiceEntity(BookingEntity booking, ServiceView service, ServicePhase phase, int sortOrder) {
        this(booking, service.id(), service.kind(), service.title(), service.price(), phase, sortOrder);
    }

    private BookedServiceEntity(
            BookingEntity booking,
            UUID serviceId,
            ServiceKind kind,
            String title,
            BigDecimal price,
            ServicePhase phase,
            int sortOrder) {
        this.booking = booking;
        this.serviceId = serviceId;
        this.kind = kind;
        this.title = title;
        this.price = price;
        this.phase = phase;
        this.sortOrder = sortOrder;
    }

    /** Та же услуга с тем же снимком в другом составе. */
    BookedServiceEntity copyAs(ServicePhase phase) {
        return new BookedServiceEntity(booking, serviceId, kind, title, price, phase, sortOrder);
    }

    ServicePhase phase() {
        return phase;
    }

    BigDecimal price() {
        return price;
    }

    BookedServiceView toView() {
        return new BookedServiceView(serviceId, kind, title, price);
    }
}
