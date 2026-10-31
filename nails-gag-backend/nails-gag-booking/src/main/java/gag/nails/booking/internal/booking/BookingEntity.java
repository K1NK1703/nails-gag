package gag.nails.booking.internal.booking;

import gag.nails.booking.error.InvalidTransitionException;
import gag.nails.booking.model.BookedServiceView;
import gag.nails.booking.model.BookingCreator;
import gag.nails.booking.model.BookingStatus;
import gag.nails.booking.model.BookingView;
import gag.nails.booking.model.CancelReason;
import gag.nails.booking.model.EarlierTimeStatus;
import gag.nails.booking.model.EarlierTimeView;
import gag.nails.booking.model.KeptPrepaymentFact;
import gag.nails.booking.model.PrepaymentOutcome;
import gag.nails.booking.model.VisitFact;
import gag.nails.booking.model.VisitServiceFact;
import gag.nails.catalog.model.ServiceView;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.InternalException;
import gag.nails.schedule.model.SlotView;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
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
public class BookingEntity {

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

    private @Nullable String amountNote;

    @Column(precision = 12, scale = 2)
    private @Nullable BigDecimal prepaymentAmount;

    @Enumerated(EnumType.STRING)
    private @Nullable PrepaymentOverride prepaymentOutcomeOverride;

    private @Nullable Instant clientErasedAt;

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

    /** Подтвердить (с предоплатой или без) можно только запись, ждущую предоплату. */
    public void requireAwaitingPrepayment() {
        if (status != BookingStatus.AWAITING_PREPAYMENT) {
            throw new InvalidTransitionException(id, status, "confirm");
        }
    }

    /**
     * Предоплата получена: запись, ждавшая её, подтверждена.
     *
     * @param amount сумма предоплаты, больше нуля
     * @throws InternalException если сумма не больше нуля — её проверяет вызывающий код
     */
    public void confirmWithPrepayment(Instant now, BigDecimal amount) {
        if (amount.signum() <= 0) {
            throw new InternalException("prepayment amount must be positive: " + amount);
        }
        confirm();
        prepaymentReceivedAt = now;
        prepaymentAmount = amount;
    }

    /** Запись, ждавшая предоплату, подтверждена без неё. */
    public void confirmWithoutPrepayment() {
        confirm();
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

    /**
     * Новое предложение заменяет прежнее, но не согласованное раннее время: оно действует, пока новое
     * предложение не принято, и остаётся в силе при отказе, отзыве и истечении.
     */
    public void proposeEarlierTime(Instant proposedStart, Instant now) {
        if (status != BookingStatus.CONFIRMED || offerStatus == EarlierTimeStatus.PENDING) {
            throw new InvalidTransitionException(id, status, "propose earlier time");
        }
        this.proposedStart = proposedStart;
        this.proposedAt = now;
        this.offerStatus = EarlierTimeStatus.PENDING;
    }

    public void withdrawEarlierTime() {
        requirePendingOffer("withdraw earlier time");
        offerStatus = EarlierTimeStatus.WITHDRAWN;
    }

    /**
     * Ответ клиента; согласиться можно только пока предложенное время не наступило. Согласие
     * заменяет согласованное время новым, отказ оставляет прежнее (если оно было).
     */
    public void answerEarlierTime(boolean accept, Instant now) {
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
    public boolean expireEarlierTime(Instant now) {
        if (offerStatus != EarlierTimeStatus.PENDING || slotStartsAt.isAfter(now)) {
            return false;
        }
        offerStatus = EarlierTimeStatus.EXPIRED;
        return true;
    }

    public boolean isPrepaymentOverdue(Instant now) {
        return status == BookingStatus.AWAITING_PREPAYMENT
                && prepaymentDeadline != null
                && prepaymentDeadline.isBefore(now);
    }

    /**
     * Визит выполнен: из активной записи либо исправлением отметки «не пришла». Фактический состав
     * копируется из выбранного при записи, итог — сумма его цен; дальше мастер правит их отдельно.
     * Отметка разрешена и записи, ждавшей предоплату (мастер решает на месте): срок предоплаты при
     * этом снимается. Исход предоплаты выполненного визита — в счёт визита, поэтому переопределение,
     * поставленное неявке, снимается.
     */
    public void complete(Instant now) {
        if (status == BookingStatus.COMPLETED || status == BookingStatus.CANCELLED) {
            throw new InvalidTransitionException(id, status, "complete");
        }
        status = BookingStatus.COMPLETED;
        completedAt = now;
        noShowAt = null;
        prepaymentDeadline = null;
        prepaymentOutcomeOverride = null;
        amountNote = null;
        expirePendingOffer();
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
     * Фактический состав, итог и причина суммы стираются.
     */
    public void markNoShow(Instant now) {
        if (status == BookingStatus.NO_SHOW || status == BookingStatus.CANCELLED) {
            throw new InvalidTransitionException(id, status, "mark no-show");
        }
        status = BookingStatus.NO_SHOW;
        noShowAt = now;
        completedAt = null;
        prepaymentDeadline = null;
        expirePendingOffer();
        services.removeIf(service -> service.phase() == ServicePhase.ACTUAL);
        finalAmount = null;
        amountNote = null;
    }

    /**
     * Фактический состав и итог выполненного визита. {@code actual} — основная услуга первой; итог — не больше двух
     * знаков после запятой (проверено командой), хранится с двумя.
     */
    public void replaceActualServices(List<ServiceView> actual, BigDecimal finalAmount) {
        if (status != BookingStatus.COMPLETED) {
            throw new InvalidTransitionException(id, status, "update actual services");
        }
        services.removeIf(service -> service.phase() == ServicePhase.ACTUAL);
        addServices(actual, ServicePhase.ACTUAL);
        this.finalAmount = finalAmount.setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * Фактический состав, итог и причина суммы выполненного визита. {@code actual} — основная услуга первой.
     *
     * @param amountNote причина суммы, уже без пробелов по краям и проверенная по длине; пусто — причины нет
     */
    public void replaceVisit(List<ServiceView> actual, BigDecimal finalAmount, @Nullable String amountNote) {
        replaceActualServices(actual, finalAmount);
        this.amountNote = amountNote;
    }

    /**
     * Исход предоплаты можно переопределить: она получена, а запись отменена или отмечена неявкой.
     *
     * @return момент отмены или неявки — от него считается срок переопределения
     * @throws InvalidTransitionException если предоплата не получена или запись не отменена и не отмечена неявкой —
     *     исход тогда в счёт визита, переопределять нечего
     */
    public Instant requirePrepaymentSettled() {
        var settledAt = switch (status) {
            case CANCELLED -> cancelledAt;
            case NO_SHOW -> noShowAt;
            case AWAITING_PREPAYMENT, CONFIRMED, COMPLETED -> null;
        };
        if (prepaymentReceivedAt == null || settledAt == null) {
            throw new InvalidTransitionException(id, status, "set prepayment outcome");
        }
        return settledAt;
    }

    /**
     * Переопределяет исход полученной предоплаты отменённой записи или неявки; значение, совпадающее с правилом,
     * снимает переопределение. Срок проверяет вызывающий код по {@link #requirePrepaymentSettled}.
     *
     * @throws InvalidTransitionException если переопределять нечего ({@link #requirePrepaymentSettled})
     */
    public void overridePrepaymentOutcome(PrepaymentOverride override) {
        requirePrepaymentSettled();
        var byRule = PrepaymentOutcomes.byRule(status, cancelReason);
        prepaymentOutcomeOverride = override.outcome() == byRule ? null : override;
    }

    /**
     * Денежный факт выполненного визита: фактический состав, его прайс, итог и причина суммы; клиента в нём нет.
     *
     * @param day день визита в поясе салона — его считает вызывающий по {@link #visitStart}
     * @throws InternalException если визит не выполнен
     */
    public VisitFact toVisitFact(LocalDate day) {
        var amount = finalAmount;
        if (status != BookingStatus.COMPLETED || amount == null) {
            throw new InternalException("visit fact of booking " + id + " in status " + status);
        }
        var actual = services.stream()
                .filter(service -> service.phase() == ServicePhase.ACTUAL)
                .map(BookedServiceEntity::toFact)
                .toList();
        var priceSum = actual.stream().map(VisitServiceFact::price).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new VisitFact(id, day, actual, priceSum, amount, amountNote);
    }

    /**
     * Удержанная предоплата: исход по правилу с учётом переопределения — {@link PrepaymentOutcome#KEPT}.
     *
     * @param day день отмены или неявки в поясе салона — его считает вызывающий по {@link #requirePrepaymentSettled}
     * @throws InternalException если предоплата не удержана
     */
    public KeptPrepaymentFact toKeptPrepaymentFact(LocalDate day) {
        var amount = prepaymentAmount;
        if (prepaymentOutcome() != PrepaymentOutcome.KEPT || amount == null) {
            throw new InternalException("prepayment of booking " + id + " is not kept");
        }
        return new KeptPrepaymentFact(id, day, amount);
    }

    /** Прайс выбранного при записи состава. */
    public BigDecimal plannedPrice() {
        return services.stream()
                .filter(service -> service.phase() == ServicePhase.PLANNED)
                .map(BookedServiceEntity::price)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Исход полученной предоплаты с учётом переопределения ({@link PrepaymentOutcomes#of}); пусто — её не было. */
    public @Nullable PrepaymentOutcome prepaymentOutcome() {
        return PrepaymentOutcomes.of(status, cancelReason, prepaymentReceivedAt != null, prepaymentOutcomeOverride);
    }

    void setComment(@Nullable String comment) {
        this.comment = blankToNull(comment);
    }

    /**
     * Обезличивание клиента: свободные тексты записи, где может оказаться имя, — комментарий клиента и причина
     * итоговой суммы — стираются в любом состоянии. Суммы остаются: это не персональные данные.
     */
    public void wipeFreeTexts() {
        this.comment = null;
        this.amountNote = null;
    }

    void setMasterNote(@Nullable String masterNote) {
        this.masterNote = blankToNull(masterNote);
    }

    /**
     * Клиент удалил аккаунт, а визит по активной записи ещё не начался: запись не отменяется — клиентка может прийти, —
     * а ждёт решения мастера. Ждущее ответа предложение приехать раньше отзывается: ответить некому. Отметка остаётся
     * и после отмены или отметки визита; повторное обезличивание её не сдвигает.
     */
    public void markClientErased(Instant now) {
        if (clientErasedAt == null) {
            clientErasedAt = now;
        }
        withdrawPendingOffer();
    }

    /**
     * Действие, о котором надо сообщить клиенту или которое пишет его текст, — перенос, предложение приехать раньше,
     * комментарий клиента, — невозможно для записи с отметкой обезличивания ({@link #markClientErased}), то есть
     * записи, которая была на рассмотрении у мастера: сообщить некому, а свободный текст обезличенной клиентки заново
     * не пишется. Мастеру остаётся оставить запись или отменить её. Проверяется отметка записи, а не карточка клиента:
     * записи, визит по которым уже начался в момент обезличивания, отметки не получают и под правило осознанно не
     * попадают — с ними дальше только отметка визита.
     *
     * @throws InvalidTransitionException если у записи есть отметка обезличивания клиента
     */
    public void requireNotMarkedClientErased(String action) {
        if (clientErasedAt != null) {
            throw new InvalidTransitionException(id, status, action);
        }
    }

    /** Объединение клиентов: запись переходит к тому, кто остался. */
    public void reassignTo(UUID clientId) {
        this.clientId = clientId;
    }

    public UUID id() {
        return id;
    }

    public UUID clientId() {
        return clientId;
    }

    UUID slotId() {
        return slotId;
    }

    public Instant slotStartsAt() {
        return slotStartsAt;
    }

    /** Время, к которому клиент приходит ({@link BookingView#visitStart(Instant, Instant)}). */
    public Instant visitStart() {
        return BookingView.visitStart(agreedStart, slotStartsAt);
    }

    public BookingStatus status() {
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
    public Instant prepaymentReceivedAt() {
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

    /** Момент обезличивания клиента, если запись ждала решения мастера ({@link #markClientErased}). */
    @Nullable
    public Instant clientErasedAt() {
        return clientErasedAt;
    }

    @Nullable
    CancelReason cancelReason() {
        return cancelReason;
    }

    @Nullable
    String masterNote() {
        return masterNote;
    }

    /**
     * Представление для {@code viewer}. Деньги — итог визита, его причина, сумма и исход предоплаты — видит только
     * сотрудник с чтением раздела «Записи»; клиенту, в том числе в его собственной записи, они не отдаются: на сайте
     * клиент видит только прайс.
     */
    public BookingView toView(Actor viewer) {
        var staff = viewer.canRead(Section.BOOKINGS);
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
                staff ? finalAmount : null,
                staff ? prepaymentAmount : null,
                staff ? prepaymentOutcome() : null,
                staff ? amountNote : null,
                clientErasedAt);
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

    private void confirm() {
        requireAwaitingPrepayment();
        status = BookingStatus.CONFIRMED;
        prepaymentDeadline = null;
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

    /** Визит отмечен: отвечать на предложение, ждавшее ответа, уже не на что. */
    private void expirePendingOffer() {
        if (offerStatus == EarlierTimeStatus.PENDING) {
            offerStatus = EarlierTimeStatus.EXPIRED;
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
