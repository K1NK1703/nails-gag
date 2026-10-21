package gag.nails.booking.internal.reference;

import gag.nails.booking.BookingReferences;
import gag.nails.booking.error.ReferencesClosedException;
import gag.nails.booking.error.ReferencesLimitReachedException;
import gag.nails.booking.internal.booking.BookingFinder;
import gag.nails.booking.internal.booking.CurrentRules;
import gag.nails.booking.model.ReferenceAuthor;
import gag.nails.booking.model.ReferenceView;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.InternalException;
import gag.nails.common.error.ValidationException;
import gag.nails.files.FileStorage;
import gag.nails.files.model.FilePurpose;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Изменения берут строку записи под блокировку до проверок ({@link BookingFinder#requireAccessibleForUpdate}):
 * параллельные добавления к одной записи считают референсы по очереди, а отмена, перенос или отметка той же записи
 * идут строго до или после. Порядок блокировок — запись, затем строка файла ({@link FileStorage#attach},
 * {@link FileStorage#delete}); блокировки клиента и слота здесь не нужны.
 */
@Service
@Transactional
class DefaultBookingReferences implements BookingReferences {

    private static final String NO_SUCH_FILE = "no such file uploaded by this account";

    private final ReferenceRepository references;
    private final ReferenceRemoval removal;
    private final BookingFinder finder;
    private final CurrentRules currentRules;
    private final FileStorage files;

    DefaultBookingReferences(
            ReferenceRepository references,
            ReferenceRemoval removal,
            BookingFinder finder,
            CurrentRules currentRules,
            FileStorage files) {
        this.references = references;
        this.removal = removal;
        this.finder = finder;
        this.currentRules = currentRules;
        this.files = files;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReferenceView> list(UUID bookingId, Actor actor) {
        finder.requireAccessible(bookingId, actor);
        return references.findAllByBookingIdOrderByCreatedAtAscFileIdAsc(bookingId).stream()
                .map(this::view)
                .toList();
    }

    @Override
    public ReferenceView add(UUID bookingId, UUID fileId, Actor actor) {
        var booking = finder.requireAccessibleForUpdate(bookingId, actor);
        var author = authorOf(actor);
        var rules = currentRules.get();
        if (author == ReferenceAuthor.CLIENT
                && (!booking.status().isActive() || rules.visitStarted(booking.slotStartsAt()))) {
            throw new ReferencesClosedException();
        }
        var uploadedBy = actor.accountId();
        if (uploadedBy == null) {
            // Загружать файлы может только аккаунт: у актора без него своих файлов нет.
            throw new ValidationException("fileId", NO_SUCH_FILE);
        }
        if (references.countByBookingId(bookingId) >= LIMIT) {
            throw new ReferencesLimitReachedException(LIMIT);
        }
        // Привязка сама проверяет назначение, загрузившего и занятость; чужой файл неотличим от несуществующего.
        files.attach(fileId, FilePurpose.REFERENCE, uploadedBy);
        var reference = references.save(
                new ReferenceEntity(booking.id(), fileId, author, rules.now().truncatedTo(ChronoUnit.MICROS)));
        return view(reference);
    }

    @Override
    public void remove(UUID bookingId, UUID fileId, Actor actor) {
        var booking = finder.requireAccessibleForUpdate(bookingId, actor);
        if (authorOf(actor) == ReferenceAuthor.CLIENT && currentRules.get().visitStarted(booking.slotStartsAt())) {
            throw new ReferencesClosedException();
        }
        references
                .findById(fileId)
                .filter(reference -> reference.bookingId().equals(booking.id()))
                .ifPresent(removal::remove);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean imageVisible(UUID bookingId, UUID fileId, Actor actor) {
        finder.requireAccessible(bookingId, actor);
        return references.existsByBookingIdAndFileId(bookingId, fileId);
    }

    /**
     * Сотрудник с изменением «Записей» действует как сотрудник, даже если запись его собственная; иначе актор —
     * клиент записи: доступ уже проверила {@link BookingFinder#requireAccessibleForUpdate}.
     */
    private static ReferenceAuthor authorOf(Actor actor) {
        return actor.canWrite(Section.BOOKINGS) ? ReferenceAuthor.STAFF : ReferenceAuthor.CLIENT;
    }

    private ReferenceView view(ReferenceEntity reference) {
        var file = files.find(reference.fileId())
                .orElseThrow(() -> new InternalException("file of reference " + reference.fileId() + " is missing"));
        return reference.toView(file);
    }
}
