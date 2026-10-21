package gag.nails.portfolio.internal.work;

import gag.nails.booking.BookingQueries;
import gag.nails.clients.ClientQueries;
import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.ValidationException;
import gag.nails.common.validation.CommandValidator;
import gag.nails.files.FileStorage;
import gag.nails.files.model.FilePurpose;
import gag.nails.portfolio.PortfolioService;
import gag.nails.portfolio.command.AddWork;
import gag.nails.portfolio.command.UpdateWork;
import gag.nails.portfolio.error.WorkNotFoundException;
import gag.nails.portfolio.model.WorkSource;
import gag.nails.portfolio.model.WorkView;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Блокировки берутся в порядке {@link PortfolioLocks}: порядок работ → клиент записи → строка работы → строка файла.
 * Поэтому у правки клиент записи определяется до блокировки строки работы.
 */
@Service
@Transactional
class DefaultPortfolioService implements PortfolioService {

    private static final String NO_SUCH_FILE = "no such file uploaded by this account";

    private final WorkRepository works;
    private final PortfolioLocks locks;
    private final FileStorage files;
    private final BookingQueries bookings;
    private final ClientQueries clients;
    private final CommandValidator validator;
    private final Clock clock;

    DefaultPortfolioService(
            WorkRepository works,
            PortfolioLocks locks,
            FileStorage files,
            BookingQueries bookings,
            ClientQueries clients,
            CommandValidator validator,
            Clock clock) {
        this.works = works;
        this.locks = locks;
        this.files = files;
        this.bookings = bookings;
        this.clients = clients;
        this.validator = validator;
        this.clock = clock;
    }

    @Override
    public WorkView add(AddWork command, Actor actor) {
        actor.requireWrite(Section.PORTFOLIO);
        validator.requireValid(command);
        requireBookingMatchesSource(command.source(), command.bookingId());
        var uploadedBy = actor.accountId();
        if (uploadedBy == null) {
            // Загружать файлы может только аккаунт: у актора без него своих файлов нет.
            throw new ValidationException("fileId", NO_SUCH_FILE);
        }
        locks.order();
        var details = details(command.alt(), command.caption(), command.source(), command.bookingId(), actor);
        var file = files.find(command.fileId()).orElseThrow(() -> new ValidationException("fileId", NO_SUCH_FILE));
        if (file.purpose() != FilePurpose.PORTFOLIO) {
            throw new ValidationException("fileId", "must be a " + FilePurpose.PORTFOLIO + " file");
        }
        files.attach(file.id(), FilePurpose.PORTFOLIO, uploadedBy);
        var position = works.findMaxPosition().map(last -> last + 1).orElse(0);
        var work = works.save(new WorkEntity(file.id(), file.width(), file.height(), details, position, now()));
        return view(work.id());
    }

    @Override
    public WorkView update(UUID workId, UpdateWork command, Actor actor) {
        actor.requireWrite(Section.PORTFOLIO);
        validator.requireValid(command);
        requireBookingMatchesSource(command.source(), command.bookingId());
        if (!works.existsById(workId)) {
            throw new WorkNotFoundException(workId);
        }
        var details = details(command.alt(), command.caption(), command.source(), command.bookingId(), actor);
        requireLocked(workId).apply(details);
        return view(workId);
    }

    @Override
    public void show(UUID workId, Actor actor) {
        actor.requireWrite(Section.PORTFOLIO);
        requireLocked(workId).setVisible(true);
    }

    @Override
    public void hide(UUID workId, Actor actor) {
        actor.requireWrite(Section.PORTFOLIO);
        requireLocked(workId).setVisible(false);
    }

    @Override
    public void reorder(List<UUID> orderedWorkIds, Actor actor) {
        actor.requireWrite(Section.PORTFOLIO);
        if (orderedWorkIds.stream().anyMatch(Objects::isNull)) {
            throw new ValidationException("orderedWorkIds", "must not contain nulls");
        }
        locks.order();
        var all = works.findAllLocked().stream().collect(Collectors.toMap(WorkEntity::id, Function.identity()));
        // Размер и множество совпадают — значит, каждая работа ровно один раз и лишних нет.
        if (orderedWorkIds.size() != all.size() || !new HashSet<>(orderedWorkIds).equals(all.keySet())) {
            throw new ValidationException("orderedWorkIds", "must list every work exactly once");
        }
        var position = 0;
        for (var id : orderedWorkIds) {
            all.get(id).setPosition(position++);
        }
    }

    @Override
    public void delete(UUID workId, Actor actor) {
        actor.requireWrite(Section.PORTFOLIO);
        var work = requireLocked(workId);
        works.delete(work);
        files.delete(work.fileId());
    }

    private static void requireBookingMatchesSource(WorkSource source, @Nullable UUID bookingId) {
        if (source == WorkSource.CLIENT && bookingId == null) {
            throw new ValidationException("bookingId", "required for a " + WorkSource.CLIENT + " work");
        }
        if (source != WorkSource.CLIENT && bookingId != null) {
            throw new ValidationException("bookingId", "allowed only for a " + WorkSource.CLIENT + " work");
        }
    }

    /** Поля работы после проверок; у работы клиента — клиент её записи под блокировкой клиента. */
    private WorkDetails details(
            String alt, @Nullable String caption, WorkSource source, @Nullable UUID bookingId, Actor actor) {
        var clientId = bookingId == null ? null : clientOf(bookingId, actor);
        var normalizedCaption = caption == null || caption.isBlank() ? null : caption;
        return new WorkDetails(alt, normalizedCaption, source, bookingId, clientId);
    }

    /**
     * Клиент записи под блокировкой клиента ({@link PortfolioLocks#client}). Под блокировкой карточка перечитывается:
     * пока ждали, её могли обезличить (работы клиента удалены — новая не заводится) или объединить с другой (запись
     * уже у оставшейся; перечитать запись в этой транзакции нельзя — она закэширована, поэтому отказ).
     */
    private UUID clientOf(UUID bookingId, Actor actor) {
        var clientId = bookings.get(bookingId, actor).clientId();
        locks.client(clientId);
        var client = clients.byId(clientId).orElseThrow(() -> new ClientNotFoundException(clientId));
        if (client.erased()) {
            throw new ValidationException("bookingId", "the client of the booking is erased");
        }
        return clientId;
    }

    private WorkEntity requireLocked(UUID workId) {
        return works.findLockedById(workId).orElseThrow(() -> new WorkNotFoundException(workId));
    }

    private WorkView view(UUID workId) {
        return works.findRowById(workId).map(WorkRow::toView).orElseThrow(() -> new WorkNotFoundException(workId));
    }

    /** Время с точностью {@code timestamptz} (микросекунды): что записано, то и прочитается. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
