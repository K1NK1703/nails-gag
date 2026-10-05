package gag.nails.booking.internal;

import gag.nails.booking.BookedServiceView;
import gag.nails.booking.BookingCreator;
import gag.nails.booking.BookingStatus;
import gag.nails.booking.BookingView;
import gag.nails.booking.CancelReason;
import gag.nails.booking.EarlierTimeStatus;
import gag.nails.booking.EarlierTimeView;
import gag.nails.booking.error.InvalidTransitionException;
import gag.nails.catalog.ServiceView;
import gag.nails.common.error.InternalException;
import gag.nails.schedule.SlotView;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

@Entity
@Table(schema = "booking", name = "booking")
class BookingEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID clientId;
    private UUID slotId;
    private Instant slotStartsAt;
    private Instant slotEndsAt;

    @Enumerated(EnumType.STRING)
    private BookingStatus status;

    @Enumerated(EnumType.STRING)
    private BookingCreator createdBy;

    private @Nullable String comment;
    private @Nullable String masterNote;
    private boolean prepaymentRequired;
    private @Nullable Instant prepaymentReceivedAt;
    private @Nullable Instant prepaymentDeadline;
    private @Nullable Instant proposedStart;
    private @Nullable Instant proposedAt;

    @Enumerated(EnumType.STRING)
    private @Nullable EarlierTimeStatus offerStatus;

    private @Nullable Instant agreedStart;

    @Enumerated(EnumType.STRING)
    private @Nullable CancelReason cancelReason;

    private @Nullable Instant cancelledAt;
    private @Nullable Instant completedAt;
    private @Nullable Instant noShowAt;

    @Column(precision = 12, scale = 2)
    private @Nullable BigDecimal finalAmount;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder")
    @BatchSize(size = 50)
    private List<BookedServiceEntity> services = new ArrayList<>();

    // Аудит, не бизнес-время: бизнес-время берётся только из Clock.
    // Меняется только при UPDATE самой строки: правка одной лишь коллекции его не сдвигает.
    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    protected BookingEntity() {}

    /**
     * Новая запись. Срок предоплаты задан — запись ждёт предоплату, не задан — подтверждена сразу.
     * {@code services} — основная услуга первой, затем дополнения.
     */
    static BookingEntity create(
            UUID clientId,
            SlotView slot,
            BookingCreator createdBy,
            @Nullable Instant prepaymentDeadline,
            @Nullable String comment,
            List<ServiceView> services) {
        var booking = new BookingEntity();
        booking.clientId = clientId;
        booking.slotId = slot.id();
        booking.slotStartsAt = slot.startsAt();
        booking.slotEndsAt = slot.endsAt();
        booking.createdBy = createdBy;
        booking.prepaymentRequired = prepaymentDeadline != null;
        booking.prepaymentDeadline = prepaymentDeadline;
        booking.status = prepaymentDeadline != null ? BookingStatus.AWAITING_PREPAYMENT : BookingStatus.CONFIRMED;
        booking.comment = blankToNull(comment);
        booking.addServices(services, ServicePhase.PLANNED);
        return booking;
    }

    void requireActive(String action) {
        if (!status.isActive()) {
            throw new InvalidTransitionException(id, status, action);
        }
    }

    /** Отмена активной записи; ждущее ответа предложение приехать раньше отзывается. */
    void cancel(CancelReason reason, Instant now) {
        requireActive("cancel");
        status = BookingStatus.CANCELLED;
        cancelReason = reason;
        cancelledAt = now;
        prepaymentDeadline = null;
        withdrawPendingOffer();
    }

    /** Запись, ждавшая предоплату, становится подтверждённой — с предоплатой или без неё. */
    void confirm(Instant now, boolean prepaymentReceived) {
        if (status != BookingStatus.AWAITING_PREPAYMENT) {
            throw new InvalidTransitionException(id, status, "confirm");
        }
        status = BookingStatus.CONFIRMED;
        prepaymentDeadline = null;
        if (prepaymentReceived) {
            prepaymentReceivedAt = now;
        }
    }

    /**
     * Перенос на другой слот: состояние сохраняется. Предложение приехать раньше относилось к
     * прежнему слоту, поэтому отзывается и ждущее ответа, и уже принятое, а согласованное раннее
     * время сбрасывается.
     *
     * @param prepaymentDeadline новый срок предоплаты: задан ровно тогда, когда запись ждёт предоплату
     * @throws InternalException если срок не согласован с состоянием — ошибка вызывающего кода
     */
    void moveTo(SlotView slot, @Nullable Instant prepaymentDeadline) {
        requireActive("reschedule");
        if ((status == BookingStatus.AWAITING_PREPAYMENT) != (prepaymentDeadline != null)) {
            throw new InternalException(
                    "prepayment deadline must be set exactly when the booking awaits prepayment: status " + status);
        }
        slotId = slot.id();
        slotStartsAt = slot.startsAt();
        slotEndsAt = slot.endsAt();
        agreedStart = null;
        this.prepaymentDeadline = prepaymentDeadline;
        withdrawOfferOnMove();
    }

    void proposeEarlierTime(Instant proposedStart, Instant now) {
        if (status != BookingStatus.CONFIRMED || offerStatus == EarlierTimeStatus.PENDING) {
            throw new InvalidTransitionException(id, status, "propose earlier time");
        }
        this.proposedStart = proposedStart;
        this.proposedAt = now;
        this.offerStatus = EarlierTimeStatus.PENDING;
    }

    void withdrawEarlierTime() {
        requirePendingOffer("withdraw earlier time");
        offerStatus = EarlierTimeStatus.WITHDRAWN;
    }

    /** Ответ клиента; согласиться можно только пока предложенное время не наступило. */
    void answerEarlierTime(boolean accept, Instant now) {
        requirePendingOffer("answer earlier time");
        if (!accept) {
            offerStatus = EarlierTimeStatus.DECLINED;
            return;
        }
        if (proposedStart == null || !now.isBefore(proposedStart)) {
            throw new InvalidTransitionException(id, status, "accept earlier time");
        }
        offerStatus = EarlierTimeStatus.ACCEPTED;
        agreedStart = proposedStart;
    }

    /** Предложение без ответа к началу слота истекает. Возвращает {@code false}, если истекать нечему. */
    boolean expireEarlierTime(Instant now) {
        if (offerStatus != EarlierTimeStatus.PENDING || slotStartsAt.isAfter(now)) {
            return false;
        }
        offerStatus = EarlierTimeStatus.EXPIRED;
        return true;
    }

    boolean isPrepaymentOverdue(Instant now) {
        return status == BookingStatus.AWAITING_PREPAYMENT
                && prepaymentDeadline != null
                && prepaymentDeadline.isBefore(now);
    }

    /**
     * Визит выполнен: из активной записи либо исправлением отметки «не пришла». Фактический состав
     * копируется из выбранного при записи, итог — сумма его цен; дальше мастер правит их отдельно.
     * Отметка разрешена и записи, ждавшей предоплату (мастер решает на месте): срок предоплаты при
     * этом снимается.
     */
    void complete(Instant now) {
        if (status == BookingStatus.COMPLETED || status == BookingStatus.CANCELLED) {
            throw new InvalidTransitionException(id, status, "complete");
        }
        status = BookingStatus.COMPLETED;
        completedAt = now;
        noShowAt = null;
        prepaymentDeadline = null;
        var actual = services.stream()
                .filter(service -> service.phase() == ServicePhase.PLANNED)
                .map(service -> service.copyAs(ServicePhase.ACTUAL))
                .toList();
        services.addAll(actual);
        finalAmount = actual.stream().map(BookedServiceEntity::price).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Клиент не пришёл: из активной записи либо исправлением отметки «выполнена». Отметка разрешена
     * и записи, ждавшей предоплату (мастер решает на месте): срок предоплаты при этом снимается.
     */
    void markNoShow(Instant now) {
        if (status == BookingStatus.NO_SHOW || status == BookingStatus.CANCELLED) {
            throw new InvalidTransitionException(id, status, "mark no-show");
        }
        status = BookingStatus.NO_SHOW;
        noShowAt = now;
        completedAt = null;
        prepaymentDeadline = null;
        services.removeIf(service -> service.phase() == ServicePhase.ACTUAL);
        finalAmount = null;
    }

    /** Фактический состав и итог выполненного визита. {@code actual} — основная услуга первой. */
    void replaceActualServices(List<ServiceView> actual, BigDecimal finalAmount) {
        if (status != BookingStatus.COMPLETED) {
            throw new InvalidTransitionException(id, status, "update actual services");
        }
        services.removeIf(service -> service.phase() == ServicePhase.ACTUAL);
        addServices(actual, ServicePhase.ACTUAL);
        this.finalAmount = finalAmount;
    }

    void setComment(@Nullable String comment) {
        this.comment = blankToNull(comment);
    }

    void setMasterNote(@Nullable String masterNote) {
        this.masterNote = blankToNull(masterNote);
    }

    /** Объединение клиентов: запись переходит к тому, кто остался. */
    void reassignTo(UUID clientId) {
        this.clientId = clientId;
    }

    UUID id() {
        return id;
    }

    UUID clientId() {
        return clientId;
    }

    UUID slotId() {
        return slotId;
    }

    Instant slotStartsAt() {
        return slotStartsAt;
    }

    BookingStatus status() {
        return status;
    }

    BookingCreator createdBy() {
        return createdBy;
    }

    @Nullable
    Instant prepaymentDeadline() {
        return prepaymentDeadline;
    }

    @Nullable
    Instant prepaymentReceivedAt() {
        return prepaymentReceivedAt;
    }

    @Nullable
    Instant proposedStart() {
        return proposedStart;
    }

    @Nullable
    Instant agreedStart() {
        return agreedStart;
    }

    @Nullable
    CancelReason cancelReason() {
        return cancelReason;
    }

    @Nullable
    String masterNote() {
        return masterNote;
    }

    BookingView toView() {
        return new BookingView(
                id,
                clientId,
                slotId,
                slotStartsAt,
                slotEndsAt,
                status,
                createdBy,
                comment,
                prepaymentRequired,
                prepaymentDeadline,
                prepaymentReceivedAt,
                earlierTime(),
                agreedStart,
                cancelReason,
                cancelledAt,
                completedAt,
                noShowAt,
                servicesOf(ServicePhase.PLANNED),
                servicesOf(ServicePhase.ACTUAL),
                finalAmount);
    }

    private @Nullable EarlierTimeView earlierTime() {
        if (offerStatus == null || proposedStart == null || proposedAt == null) {
            return null;
        }
        return new EarlierTimeView(proposedStart, proposedAt, offerStatus);
    }

    private List<BookedServiceView> servicesOf(ServicePhase phase) {
        return services.stream()
                .filter(service -> service.phase() == phase)
                .map(BookedServiceEntity::toView)
                .toList();
    }

    private void addServices(List<ServiceView> views, ServicePhase phase) {
        for (var order = 0; order < views.size(); order++) {
            services.add(new BookedServiceEntity(this, views.get(order), phase, order));
        }
    }

    private void requirePendingOffer(String action) {
        if (offerStatus != EarlierTimeStatus.PENDING) {
            throw new InvalidTransitionException(id, status, action);
        }
    }

    private void withdrawPendingOffer() {
        if (offerStatus == EarlierTimeStatus.PENDING) {
            offerStatus = EarlierTimeStatus.WITHDRAWN;
        }
    }

    /** При переносе теряет смысл и ждущее ответа, и принятое предложение: оба относились к прежнему слоту. */
    private void withdrawOfferOnMove() {
        if (offerStatus == EarlierTimeStatus.PENDING || offerStatus == EarlierTimeStatus.ACCEPTED) {
            offerStatus = EarlierTimeStatus.WITHDRAWN;
        }
    }

    private static @Nullable String blankToNull(@Nullable String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
