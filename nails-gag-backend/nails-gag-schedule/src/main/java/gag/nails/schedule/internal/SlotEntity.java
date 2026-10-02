package gag.nails.schedule.internal;

import gag.nails.schedule.SlotView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

@Entity
@Table(schema = "schedule", name = "slot")
class SlotEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private LocalDate day;
    private Instant startsAt;
    private Instant endsAt;
    private int durationMinutes;
    private boolean occupied;
    private @Nullable UUID bookingId;

    // Аудит, не бизнес-время: бизнес-время берётся только из Clock.
    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    protected SlotEntity() {}

    SlotEntity(LocalDate day, Instant startsAt, int durationMinutes) {
        this.day = day;
        this.durationMinutes = durationMinutes;
        this.startsAt = startsAt;
        this.endsAt = endOf(startsAt, durationMinutes);
    }

    /** Сдвиг в пределах того же дня; длительность — снимок, не пересчитывается. */
    void moveTo(Instant startsAt) {
        this.startsAt = startsAt;
        this.endsAt = endOf(startsAt, durationMinutes);
    }

    UUID id() {
        return id;
    }

    LocalDate day() {
        return day;
    }

    Instant startsAt() {
        return startsAt;
    }

    Instant endsAt() {
        return endsAt;
    }

    boolean occupied() {
        return occupied;
    }

    SlotView toView() {
        return new SlotView(id, day, startsAt, endsAt, durationMinutes, occupied, bookingId);
    }

    private static Instant endOf(Instant startsAt, int durationMinutes) {
        return startsAt.plus(Duration.ofMinutes(durationMinutes));
    }
}
