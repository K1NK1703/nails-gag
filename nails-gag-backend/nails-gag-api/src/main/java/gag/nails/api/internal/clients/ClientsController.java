package gag.nails.api.internal.clients;

import gag.nails.api.internal.http.BookingScope;
import gag.nails.api.internal.http.SetMasterNote;
import gag.nails.booking.BookingQueries;
import gag.nails.booking.model.BookingView;
import gag.nails.clients.ClientQueries;
import gag.nails.clients.ClientService;
import gag.nails.clients.command.CreateClient;
import gag.nails.clients.command.RecordConsent;
import gag.nails.clients.command.UpdateClient;
import gag.nails.clients.model.ClientView;
import gag.nails.clients.model.ConsentView;
import gag.nails.clients.model.MergeResult;
import gag.nails.clients.model.PrepaymentFlagChangeView;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.paging.Paged;
import gag.nails.common.paging.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Карточки клиентов (спецификация API, §7.4): сотруднику — по уровню раздела «Клиенты», клиенту — своя карточка;
 * права проверяет модуль. Регистрация, подтверждение телефона, обезличивание и объединение своих карточек по HTTP не
 * выходят — их вызывают другие модули.
 */
@RestController
@RequestMapping("/api/clients")
class ClientsController {

    /** Номер страницы поиска по умолчанию — первая. */
    private static final String FIRST_PAGE = "0";

    /** Размер страницы поиска по умолчанию; наибольший — {@link Paging#MAX_SIZE}, больше — {@code 400}. */
    private static final String DEFAULT_PAGE_SIZE = "50";

    private final ClientQueries queries;
    private final ClientService clients;
    private final BookingQueries bookingQueries;

    ClientsController(ClientQueries queries, ClientService clients, BookingQueries bookingQueries) {
        this.queries = queries;
        this.clients = clients;
        this.bookingQueries = bookingQueries;
    }

    /**
     * Поиск по имени, фамилии, телефону и номеру для связи, постранично; без {@code query} — все клиенты по алфавиту.
     * Нужен уровень чтения раздела «Клиенты». Номер и размер страницы проверяет {@link Paging}.
     */
    @GetMapping
    Paged<ClientView> search(
            @RequestParam(defaultValue = "") @Size(max = 100) String query,
            @RequestParam(defaultValue = FIRST_PAGE) int page,
            @RequestParam(defaultValue = DEFAULT_PAGE_SIZE) int size,
            Actor actor) {
        return queries.search(query, new Paging(page, size), actor);
    }

    /** Карточка без аккаунта; нужен уровень изменения раздела «Клиенты». Занятый телефон — {@code 409}. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ClientView create(@Valid @RequestBody CreateClient body, Actor actor) {
        return clients.create(body, actor);
    }

    /**
     * Карточка с тем, что к ней прилагается, — четыре чтения модуля подряд, а не один снимок: между ними карточку могут
     * изменить. Права проверяет модуль на каждом чтении: карточку — сотруднику с чтением «Клиентов» или самому
     * клиенту; заметку и причину блокировки читает только такой сотрудник — клиенту они не видны и не запрашиваются.
     * Последнее изменение признака предоплаты модуль отдаёт без проверки прав, поэтому оно читается последним, после
     * проверенного чтения карточки.
     */
    @GetMapping("/{id}")
    ClientDetails details(@PathVariable UUID id, Actor actor) {
        var client = queries.get(id, actor);
        var staff = actor.canRead(Section.CLIENTS);
        return new ClientDetails(
                client,
                staff ? queries.blockReason(id, actor).orElse(null) : null,
                staff ? queries.masterNote(id, actor).orElse(null) : null,
                queries.lastPrepaymentChange(id).orElse(null));
    }

    /** Правка данных карточки, кроме телефона; изменение раздела «Клиенты» или сам клиент. */
    @PatchMapping("/{id}")
    ClientView update(@PathVariable UUID id, @Valid @RequestBody UpdateClient body, Actor actor) {
        return clients.update(id, body, actor);
    }

    /** Доверенный номер меняет только сотрудник с изменением «Клиентов»: телефон — ключ объединения карточек. */
    @PostMapping("/{id}/phone")
    ClientView changePhone(@PathVariable UUID id, @Valid @RequestBody ChangePhone body, Actor actor) {
        return clients.changePhone(id, body.phone(), actor);
    }

    /**
     * Номер для связи: не подтверждён и не уникален. Изменение раздела «Клиенты» или сам клиент; {@code null} стирает
     * номер.
     */
    @PutMapping("/{id}/contact-phone")
    ClientView changeContactPhone(@PathVariable UUID id, @Valid @RequestBody ChangeContactPhone body, Actor actor) {
        return clients.changeContactPhone(id, body.phone(), actor);
    }

    /** Заметка мастера целиком; нужен уровень изменения раздела «Клиенты». Пустая строка стирает заметку. */
    @PutMapping("/{id}/note")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void updateMasterNote(@PathVariable UUID id, @Valid @RequestBody SetMasterNote body, Actor actor) {
        clients.updateMasterNote(id, body.note(), actor);
    }

    /** Журнал согласий, новые первыми. */
    @GetMapping("/{id}/consents")
    List<ConsentView> consents(@PathVariable UUID id, Actor actor) {
        return queries.consents(id, actor);
    }

    /** Согласие на действующую редакцию документа; клиент — только своё и только галочкой на сайте. */
    @PostMapping("/{id}/consents")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void recordConsent(@PathVariable UUID id, @Valid @RequestBody RecordConsent body, Actor actor) {
        clients.recordConsent(id, body, actor);
    }

    /** Журнал признака предоплаты, новые первыми. */
    @GetMapping("/{id}/prepayment-history")
    List<PrepaymentFlagChangeView> prepaymentHistory(@PathVariable UUID id, Actor actor) {
        return queries.prepaymentHistory(id, actor);
    }

    /**
     * Признак обязательной предоплаты вручную; нужен уровень изменения раздела «Клиенты». Каждый вызов попадает в
     * журнал признака, даже если значение не поменялось.
     */
    @PutMapping("/{id}/prepayment-required")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setPrepaymentRequired(@PathVariable UUID id, @Valid @RequestBody SetPrepaymentRequired body, Actor actor) {
        clients.setPrepaymentRequired(id, body.required(), body.comment(), actor);
    }

    /** Блокировка: клиент не сможет входить и записываться сам. Повторная блокировка меняет причину. */
    @PostMapping("/{id}/block")
    ClientView block(@PathVariable UUID id, @Valid @RequestBody BlockClient body, Actor actor) {
        return clients.block(id, body.reason(), actor);
    }

    /** Снятие блокировки, причина стирается; незаблокированный клиент остаётся как есть. */
    @PostMapping("/{id}/unblock")
    ClientView unblock(@PathVariable UUID id, Actor actor) {
        return clients.unblock(id, actor);
    }

    /**
     * Объединение двух карточек одного человека; нужен уровень изменения раздела «Клиенты». Остаётся карточка
     * {@code {id}}, кроме одного случая: аккаунт входа есть только у {@code sourceId} — тогда остаётся она. Вторая
     * карточка удаляется, её журналы и недостающие данные переходят оставшейся. В ответе — {@code survivorId}
     * (оставшаяся) и {@code removedId} (удалённая).
     */
    @PostMapping("/{id}/merge")
    MergeResult merge(@PathVariable UUID id, @Valid @RequestBody MergeClients body, Actor actor) {
        return clients.merge(body.sourceId(), id, actor);
    }

    /** Активные записи клиента или его история; права — по разделу «Записи» или своя карточка, проверяет модуль. */
    @GetMapping("/{id}/bookings")
    List<BookingView> bookings(@PathVariable UUID id, @RequestParam BookingScope scope, Actor actor) {
        return switch (scope) {
            case ACTIVE -> bookingQueries.activeOfClient(id, actor);
            case HISTORY -> bookingQueries.historyOfClient(id, actor);
        };
    }
}
