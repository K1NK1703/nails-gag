package gag.nails.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.booking.BookingService;
import gag.nails.booking.command.CreateBooking;
import gag.nails.booking.model.BookingView;
import gag.nails.clients.ClientService;
import gag.nails.clients.command.CreateClient;
import gag.nails.clients.command.RecordConsent;
import gag.nails.clients.command.RegisterClient;
import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentSource;
import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.event.DomainEvent;
import gag.nails.identity.StaffDirectory;
import gag.nails.identity.model.StaffRecipient;
import gag.nails.notifications.model.MessageKind;
import gag.nails.notifications.model.MessageStatus;
import gag.nails.schedule.ScheduleService;
import gag.nails.schedule.model.SlotView;
import gag.nails.testsupport.MutableClock;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Фиксирующие тесты очереди. Слушатели событий записи ({@code @ApplicationModuleListener}) срабатывают после фиксации
 * и в другом потоке, поэтому тест фиксирует свои данные: операции записей — их собственными транзакциями, прочие
 * события — публикацией в своей транзакции ({@link #publish}). Обработку тест ждёт по реестру публикаций
 * ({@link #awaitProcessed}): публикация заносится в реестр в транзакции события и удаляется, когда слушатель
 * завершился успешно ({@code completion-mode: delete}).
 *
 * <p>После каждого теста удаляется только своё: сообщения своих записей, оставшиеся публикации о них, записи,
 * согласия и карточки своих клиентов, свои слоты и аккаунты. Часы тестов стоят на четверге 1 октября 2026, 09:00 по
 * Москве (06:00 UTC) — вне тихих часов.
 */
abstract class QueueTestSupport {

    /** Основная услуга из seed каталога. */
    static final UUID MANICURE = UUID.fromString("019a0c00-0000-7000-8000-000000000001");

    static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    static final LocalDate TUESDAY = LocalDate.of(2026, 10, 6);

    /** Сколько ждать обработки события слушателем. */
    static final Duration WAIT = Duration.ofSeconds(20);

    /** Срок жизни событийного сообщения после его {@code due_at}. */
    static final Duration EXPIRY = Duration.ofHours(24);

    /** Состояние публикации, которую слушатель не обработал. */
    static final String FAILED = "FAILED";

    /** Версия политики и оферты из seed модуля clients. */
    private static final String DOCUMENTS_VERSION = "2026-09-25";

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    ClientService clients;

    @Autowired
    ScheduleService schedule;

    @Autowired
    BookingService bookings;

    @Autowired
    StaffDirectory staffDirectory;

    @Autowired
    MutableClock clock;

    final Actor master = Actor.master();

    private final List<UUID> clientIds = new ArrayList<>();
    private final List<UUID> slotIds = new ArrayList<>();
    private final List<UUID> bookingIds = new ArrayList<>();
    private final List<UUID> accountIds = new ArrayList<>();

    @AfterEach
    void removeCommitted() {
        if (!bookingIds.isEmpty()) {
            // Слушатель, ещё работающий над событием теста, мог бы вставить сообщение уже после уборки.
            awaitSettled(bookingIds);
            delete("delete from notifications.message where booking_id in (:ids)", bookingIds);
            for (var bookingId : bookingIds) {
                jdbc.sql("delete from modulith.event_publication where serialized_event like :pattern")
                        .param("pattern", "%" + bookingId + "%")
                        .update();
            }
        }
        if (!clientIds.isEmpty()) {
            var ofClients = "select id from booking.booking where client_id in (:ids)";
            delete("delete from booking.booking_service where booking_id in (" + ofClients + ")", clientIds);
            delete("delete from booking.booking where client_id in (:ids)", clientIds);
            delete("delete from booking.client_lock where id in (:ids)", clientIds);
            delete("delete from clients.consent where client_id in (:ids)", clientIds);
            delete("delete from clients.prepayment_flag_change where client_id in (:ids)", clientIds);
            delete("delete from clients.client where id in (:ids)", clientIds);
        }
        if (!slotIds.isEmpty()) {
            delete("delete from schedule.slot where id in (:ids)", slotIds);
        }
        if (!accountIds.isEmpty()) {
            delete("delete from notifications.link_token where account_id in (:ids)", accountIds);
            delete("delete from notifications.telegram_chat where account_id in (:ids)", accountIds);
            delete("delete from notifications.preference where account_id in (:ids)", accountIds);
            // Роли аккаунта удаляются каскадом.
            delete("delete from identity.account where id in (:ids)", accountIds);
        }
        clientIds.clear();
        slotIds.clear();
        bookingIds.clear();
        accountIds.clear();
    }

    /** Клиентка с согласиями на действующие политику и оферту — может записываться сама. */
    UUID client(String firstName) {
        return client(firstName, null, null);
    }

    /** Карточка без аккаунта, заведённая мастером, — с фамилией и почтой, если заданы, и с согласиями. */
    UUID client(String firstName, @Nullable String lastName, @Nullable String email) {
        var id = clients.create(new CreateClient(firstName, lastName, null, null, email, null), master)
                .id();
        return consented(id);
    }

    /** Клиентка, зарегистрированная со своим аккаунтом входа, с почтой и согласиями. */
    Registered registeredClient(String firstName, String email) {
        var accountId = account();
        var clientId = clients.register(new RegisterClient(accountId, firstName, null, email))
                .id();
        return new Registered(consented(clientId), accountId);
    }

    /** Карточка аккаунта сотрудника с почтой: так у сотрудника появляется почта для уведомлений. */
    UUID cardOf(UUID accountId, String firstName, String email) {
        var id = clients.register(new RegisterClient(accountId, firstName, null, email))
                .id();
        clientIds.add(id);
        return id;
    }

    /** Привязывает Telegram-бота к аккаунту — строкой напрямую, как это сделает привязка по ссылке. */
    void linkTelegram(UUID accountId, long chatId) {
        jdbc.sql("insert into notifications.telegram_chat (id, account_id, chat_id, linked_at) values (?, ?, ?, ?)")
                .params(UUID.randomUUID(), accountId, chatId, Timestamp.from(clock.instant()))
                .update();
    }

    private UUID consented(UUID clientId) {
        clientIds.add(clientId);
        for (var kind : List.of(ConsentKind.PERSONAL_DATA, ConsentKind.OFFER)) {
            clients.recordConsent(
                    clientId, new RecordConsent(kind, DOCUMENTS_VERSION, ConsentSource.ENTERED_BY_MASTER), master);
        }
        return clientId;
    }

    /** Клиентка с признаком обязательной предоплаты: её самозапись ждёт предоплату. */
    UUID clientOwingPrepayment(String firstName) {
        var id = client(firstName);
        clients.setPrepaymentRequired(id, true, null, master);
        return id;
    }

    SlotView slot(LocalDate day, String time) {
        var slot = schedule.addSlot(day, LocalTime.parse(time), master);
        slotIds.add(slot.id());
        return slot;
    }

    /** Аккаунт сотрудника без карточки клиента со встроенной ролью; строки — напрямую, мимо журнала прав. */
    UUID staffAccount(String roleCode) {
        var accountId = account();
        jdbc.sql("""
                        insert into identity.account_role (account_id, role_id)
                        select ?, id from identity.role where code = ?
                        """).params(accountId, roleCode).update();
        return accountId;
    }

    /** Аккаунт входа без ролей и способов входа; строка — напрямую. Убирается после теста с его каналами. */
    UUID account() {
        var accountId = UUID.randomUUID();
        accountIds.add(accountId);
        jdbc.sql("insert into identity.account (id, created_at, last_login_at) values (?, ?, ?)")
                .params(accountId, Timestamp.from(clock.instant()), Timestamp.from(clock.instant()))
                .update();
        return accountId;
    }

    /** Клиентка записывается сама на маникюр; ждёт обработки событий записи. */
    BookingView bookByClient(UUID clientId, SlotView slot) {
        var booking = bookings.createByClient(manicure(clientId, slot), Actor.client(clientId));
        return processed(booking);
    }

    /** Мастер записывает клиентку на маникюр; ждёт обработки событий записи. */
    BookingView bookByMaster(UUID clientId, SlotView slot, boolean awaitPrepayment) {
        var booking = bookings.createByMaster(manicure(clientId, slot), awaitPrepayment, master);
        return processed(booking);
    }

    /** Регистрирует запись теста и ждёт обработки её событий. */
    BookingView processed(BookingView booking) {
        bookingIds.add(booking.id());
        awaitProcessed(booking.id());
        return booking;
    }

    /** Публикует событие записи {@code bookingId} в своей транзакции, фиксирует её и ждёт обработки. */
    void publish(UUID bookingId, DomainEvent event) {
        bookingIds.add(bookingId);
        transaction.executeWithoutResult(status -> publisher.publishEvent(event));
        awaitProcessed(bookingId);
    }

    /** Регистрирует запись теста, которой нет в модуле записей (события о ней публикует сам тест). */
    UUID bookingId() {
        var id = UUID.randomUUID();
        bookingIds.add(id);
        return id;
    }

    /**
     * Ждёт, пока в реестре не останется публикаций о записи: слушатели завершились успешно. Публикация, которую
     * слушатель не обработал (упал), остаётся в состоянии {@code FAILED} — тогда ожидание сразу кончается ошибкой с
     * состояниями публикаций; зависшая — ошибкой по таймауту.
     */
    void awaitProcessed(UUID bookingId) {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (true) {
            var statuses = publicationStatuses(bookingId);
            if (statuses.isEmpty()) {
                return;
            }
            if (statuses.contains(FAILED)) {
                throw new AssertionError("publications of " + bookingId + " failed: " + statuses);
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("publications of " + bookingId + " are not completed: " + statuses);
            }
            pause();
        }
    }

    /**
     * Для уборки: ждёт (всего не дольше {@link #WAIT}), пока у публикаций о записях теста не останется работающих
     * слушателей — публикаций нет или все они {@code FAILED}. Не бросает: уборка идёт дальше в любом случае.
     */
    private void awaitSettled(Collection<UUID> bookingIds) {
        var deadline = System.nanoTime() + WAIT.toNanos();
        try {
            for (var bookingId : bookingIds) {
                while (System.nanoTime() < deadline
                        && publicationStatuses(bookingId).stream().anyMatch(status -> !status.equals(FAILED))) {
                    pause();
                }
            }
        } catch (RuntimeException | AssertionError e) {
            // Уборка не должна падать из-за ожидания; оставшееся удаляется ниже.
        }
    }

    /** Состояния публикаций реестра, в событии которых упомянут {@code id}. */
    List<String> publicationStatuses(UUID id) {
        return jdbc.sql("""
                        select coalesce(status, 'NULL') from modulith.event_publication
                        where serialized_event like :pattern
                        """)
                .param("pattern", "%" + id + "%")
                .query(String.class)
                .list();
    }

    /** Сообщения о событиях записи — без напоминаний: их ставит в очередь свой планировщик и проверяет свой тест. */
    List<QueuedMessage> messages(UUID bookingId) {
        return jdbc.sql("""
                        select kind, client_id, account_id, due_at, expires_at, status from notifications.message
                        where booking_id = :bookingId and kind not like 'REMINDER\\_%'
                        """)
                .param("bookingId", bookingId)
                .query(QueueTestSupport::message)
                .list();
    }

    List<String> dedupKeys(UUID bookingId) {
        return jdbc.sql("select dedup_key from notifications.message where booking_id = :bookingId")
                .param("bookingId", bookingId)
                .query(String.class)
                .list();
    }

    /** Те, кто сейчас получает мастерские сообщения. */
    Set<UUID> staff() {
        return staffDirectory.recipients(Section.BOOKINGS, AccessLevel.READ).stream()
                .map(StaffRecipient::accountId)
                .collect(Collectors.toSet());
    }

    /** Мастерские сообщения в тесте не пустые: среди получателей есть сотрудник теста. */
    void assertStaffIncludes(UUID accountId) {
        assertThat(staff()).contains(accountId);
    }

    /** Ожидаемое сообщение клиентке: в очереди, живёт сутки от {@code dueAt}. */
    static QueuedMessage toClient(MessageKind kind, UUID clientId, Instant dueAt) {
        return new QueuedMessage(kind, clientId, null, dueAt, dueAt.plus(EXPIRY), MessageStatus.PENDING);
    }

    /** Ожидаемые мастерские сообщения: по одному каждому сотруднику из {@code staff}. */
    static List<QueuedMessage> toStaff(MessageKind kind, Collection<UUID> staff, Instant dueAt) {
        return staff.stream()
                .map(accountId ->
                        new QueuedMessage(kind, null, accountId, dueAt, dueAt.plus(EXPIRY), MessageStatus.PENDING))
                .toList();
    }

    @SafeVarargs
    static List<QueuedMessage> all(List<QueuedMessage>... parts) {
        var all = new ArrayList<QueuedMessage>();
        for (var part : parts) {
            all.addAll(part);
        }
        return all;
    }

    private static CreateBooking manicure(UUID clientId, SlotView slot) {
        return new CreateBooking(clientId, slot.id(), MANICURE, List.of(), null);
    }

    private static QueuedMessage message(ResultSet row, int rowNumber) throws SQLException {
        return new QueuedMessage(
                MessageKind.valueOf(row.getString("kind")),
                row.getObject("client_id", UUID.class),
                row.getObject("account_id", UUID.class),
                row.getTimestamp("due_at").toInstant(),
                row.getTimestamp("expires_at").toInstant(),
                MessageStatus.valueOf(row.getString("status")));
    }

    private void delete(String sql, Collection<UUID> ids) {
        jdbc.sql(sql).param("ids", ids).update();
    }

    static void pause() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Карточка клиентки и её аккаунт входа. */
    record Registered(UUID clientId, UUID accountId) {}

    /** Строка очереди, как её видит тест. */
    record QueuedMessage(
            MessageKind kind,
            @Nullable UUID clientId,
            @Nullable UUID accountId,
            Instant dueAt,
            Instant expiresAt,
            MessageStatus status) {}
}
