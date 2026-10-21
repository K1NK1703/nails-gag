package gag.nails.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.files.model.FilePurpose;
import gag.nails.portfolio.command.AddWork;
import gag.nails.portfolio.command.UpdateWork;
import gag.nails.portfolio.error.WorkNotFoundException;
import gag.nails.portfolio.model.WorkConsent;
import gag.nails.portfolio.model.WorkSource;
import gag.nails.portfolio.model.WorkView;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

@PortfolioModuleTest
class PortfolioServiceTest extends PortfolioTestSupport {

    @Test
    void addAttachesFileAndAppendsToEnd() {
        var first = example("Первая");
        var fileId = upload();

        var second = portfolio.add(new AddWork(fileId, "Френч", "  ", WorkSource.EXAMPLE, null), MASTER);

        assertThat(second)
                .isEqualTo(new WorkView(
                        second.id(),
                        fileId,
                        "Френч",
                        null,
                        second.position(),
                        true,
                        WorkSource.EXAMPLE,
                        null,
                        null,
                        640,
                        480,
                        true,
                        WorkConsent.NOT_NEEDED,
                        clock.instant()));
        assertThat(second.position()).isGreaterThan(first.position());
        assertThat(view(second.id())).isEqualTo(second);
        assertThat(attachedAt(fileId)).isEqualTo(clock.instant());
        assertThat(queries.all(MASTER)).extracting(WorkView::id).containsExactly(first.id(), second.id());
    }

    @Test
    void clientSourceTakesClientFromBooking() {
        var card = card("Мария");
        var booking = booking(card);

        var work = portfolio.add(new AddWork(upload(), "Френч", "Нюд", WorkSource.CLIENT, booking), MASTER);

        assertThat(work.source()).isEqualTo(WorkSource.CLIENT);
        assertThat(work.bookingId()).isEqualTo(booking);
        assertThat(work.clientId()).isEqualTo(card);
        assertThat(work.caption()).isEqualTo("Нюд");
        assertThat(work.consent()).isEqualTo(WorkConsent.MISSING);
        assertThat(work.published()).isFalse();
    }

    @Test
    void clientSourceNeedsBooking() {
        var fileId = upload();

        assertInvalid(
                () -> portfolio.add(new AddWork(fileId, "Френч", null, WorkSource.CLIENT, null), MASTER), "bookingId");
        assertThat(attachedAt(fileId)).isNull();
    }

    @Test
    void exampleMustNotHaveBooking() {
        var booking = booking(card("Мария"));

        assertInvalid(
                () -> portfolio.add(new AddWork(upload(), "Френч", null, WorkSource.EXAMPLE, booking), MASTER),
                "bookingId");
        assertInvalid(
                () -> portfolio.add(
                        new AddWork(upload(), "Френч", null, WorkSource.CLIENT_OUTSIDE_SITE, booking), MASTER),
                "bookingId");
        assertThat(queries.all(MASTER)).isEmpty();
    }

    @Test
    void clientOfErasedCardIsRejected() {
        var card = card("Мария");
        var booking = booking(card);
        clients.erase(card);

        assertInvalid(
                () -> portfolio.add(new AddWork(upload(), "Френч", null, WorkSource.CLIENT, booking), MASTER),
                "bookingId");
        assertThat(queries.all(MASTER)).isEmpty();
    }

    @Test
    void fileOfOtherPurposeOrUploaderIsRejected() {
        var reference = upload(FilePurpose.REFERENCE, MASTER);
        var foreign = upload(FilePurpose.PORTFOLIO, VIEWER);

        assertInvalid(() -> portfolio.add(exampleOf(reference), MASTER), "fileId");
        assertInvalid(() -> portfolio.add(exampleOf(foreign), MASTER), "fileId");
        assertInvalid(() -> portfolio.add(exampleOf(UUID.randomUUID()), MASTER), "fileId");
        // У актора без аккаунта загруженных файлов не бывает.
        assertInvalid(() -> portfolio.add(exampleOf(upload()), Actor.master()), "fileId");
        assertThat(attachedAt(reference)).isNull();
        assertThat(attachedAt(foreign)).isNull();
        assertThat(queries.all(MASTER)).isEmpty();
    }

    @Test
    void fileCannotBeUsedTwice() {
        var work = example("Пример");

        assertInvalid(() -> portfolio.add(exampleOf(work.fileId()), MASTER), "fileId");
        assertThat(queries.all(MASTER)).extracting(WorkView::id).containsExactly(work.id());
    }

    @Test
    void commandFieldsAreValidated() {
        assertInvalid(() -> portfolio.add(new AddWork(upload(), " ", null, WorkSource.EXAMPLE, null), MASTER), "alt");
        assertInvalid(
                () -> portfolio.add(new AddWork(upload(), "Френч", "x".repeat(201), WorkSource.EXAMPLE, null), MASTER),
                "caption");
        var work = example("Пример");
        assertInvalid(
                () -> portfolio.update(
                        work.id(), new UpdateWork("x".repeat(201), null, WorkSource.EXAMPLE, null), MASTER),
                "alt");
    }

    @Test
    void updateAppliesSourceRules() {
        var card = card("Мария");
        var booking = booking(card);
        var work = example("Пример");

        var updated = portfolio.update(work.id(), new UpdateWork("Френч", "Нюд", WorkSource.CLIENT, booking), MASTER);

        assertThat(updated.alt()).isEqualTo("Френч");
        assertThat(updated.caption()).isEqualTo("Нюд");
        assertThat(updated.clientId()).isEqualTo(card);
        assertThat(updated.bookingId()).isEqualTo(booking);
        assertThat(updated.position()).isEqualTo(work.position());
        assertThat(updated.fileId()).isEqualTo(work.fileId());
        assertThat(view(work.id())).isEqualTo(updated);

        var back =
                portfolio.update(work.id(), new UpdateWork("Френч", "", WorkSource.CLIENT_OUTSIDE_SITE, null), MASTER);

        assertThat(back.clientId()).isNull();
        assertThat(back.bookingId()).isNull();
        assertThat(back.caption()).isNull();
        assertThat(back.consent()).isEqualTo(WorkConsent.OUTSIDE_SITE);
        assertInvalid(
                () -> portfolio.update(work.id(), new UpdateWork("Френч", null, WorkSource.CLIENT, null), MASTER),
                "bookingId");
        assertInvalid(
                () -> portfolio.update(work.id(), new UpdateWork("Френч", null, WorkSource.EXAMPLE, booking), MASTER),
                "bookingId");
        assertThatThrownBy(() -> portfolio.update(
                        UUID.randomUUID(), new UpdateWork("Френч", null, WorkSource.EXAMPLE, null), MASTER))
                .isInstanceOf(WorkNotFoundException.class);
    }

    @Test
    void reorder() {
        var first = example("Первая");
        var second = example("Вторая");
        var third = example("Третья");

        portfolio.reorder(List.of(third.id(), first.id(), second.id()), MASTER);

        assertThat(queries.all(MASTER)).extracting(WorkView::id).containsExactly(third.id(), first.id(), second.id());
        assertThat(publishedIds()).containsExactly(third.id(), first.id(), second.id());
        assertInvalid(() -> portfolio.reorder(List.of(first.id(), second.id()), MASTER), "orderedWorkIds");
        assertInvalid(
                () -> portfolio.reorder(List.of(first.id(), second.id(), third.id(), first.id()), MASTER),
                "orderedWorkIds");
        assertInvalid(
                () -> portfolio.reorder(List.of(first.id(), second.id(), UUID.randomUUID()), MASTER), "orderedWorkIds");
        assertThat(queries.all(MASTER)).extracting(WorkView::id).containsExactly(third.id(), first.id(), second.id());
        // Новая работа после перестановки — в конце.
        assertThat(example("Четвёртая").position())
                .isGreaterThan(view(second.id()).position());
    }

    @Test
    void showHide() {
        var work = example("Пример");

        portfolio.hide(work.id(), MASTER);
        portfolio.hide(work.id(), MASTER);

        assertThat(view(work.id()).visible()).isFalse();
        assertThat(publishedIds()).isEmpty();

        portfolio.show(work.id(), MASTER);
        portfolio.show(work.id(), MASTER);

        assertThat(view(work.id()).visible()).isTrue();
        assertThat(publishedIds()).containsExactly(work.id());
        assertThatThrownBy(() -> portfolio.show(UUID.randomUUID(), MASTER)).isInstanceOf(WorkNotFoundException.class);
        assertThatThrownBy(() -> portfolio.hide(UUID.randomUUID(), MASTER)).isInstanceOf(WorkNotFoundException.class);
    }

    @Test
    void deleteRemovesFile() {
        var work = example("Пример");
        var kept = example("Остаётся");

        portfolio.delete(work.id(), MASTER);

        assertThatThrownBy(() -> view(work.id())).isInstanceOf(WorkNotFoundException.class);
        assertThat(files.find(work.fileId())).isEmpty();
        assertThat(files.find(kept.fileId())).isPresent();
        assertThat(queries.all(MASTER)).extracting(WorkView::id).containsExactly(kept.id());
        assertThatThrownBy(() -> portfolio.delete(work.id(), MASTER)).isInstanceOf(WorkNotFoundException.class);
    }

    @Test
    void writeNeedsPortfolioRights() {
        var work = example("Пример");
        var fileId = upload(FilePurpose.PORTFOLIO, VIEWER);
        var client = Actor.client(card("Мария"));

        for (var actor : List.of(VIEWER, client)) {
            assertDenied(() -> portfolio.add(exampleOf(fileId), actor));
            assertDenied(
                    () -> portfolio.update(work.id(), new UpdateWork("Френч", null, WorkSource.EXAMPLE, null), actor));
            assertDenied(() -> portfolio.show(work.id(), actor));
            assertDenied(() -> portfolio.hide(work.id(), actor));
            assertDenied(() -> portfolio.reorder(List.of(work.id()), actor));
            assertDenied(() -> portfolio.delete(work.id(), actor));
        }
        assertThat(attachedAt(fileId)).isNull();
        assertThat(queries.all(MASTER)).singleElement().isEqualTo(work);
    }

    private static AddWork exampleOf(UUID fileId) {
        return new AddWork(fileId, "Френч", null, WorkSource.EXAMPLE, null);
    }

    /** Когда файл привязан; пусто — «сирота». */
    private @Nullable Instant attachedAt(UUID fileId) {
        // Строка файла ровно одна; null в ней — значение колонки, а не отсутствие строки.
        var rows = jdbc.sql("select attached_at from files.stored_file where id = :id")
                .param("id", fileId)
                .query((row, number) -> Optional.ofNullable(row.getObject("attached_at", OffsetDateTime.class)))
                .list();
        assertThat(rows).hasSize(1);
        return rows.getFirst().map(OffsetDateTime::toInstant).orElse(null);
    }

    private static void assertInvalid(ThrowingCallable call, String field) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ValidationException.class, e -> assertThat(e.field()).isEqualTo(field));
    }

    private static void assertDenied(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(AccessDeniedException.class);
    }
}
