package gag.nails.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.clients.command.RecordConsent;
import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentSource;
import gag.nails.common.actor.Actor;
import gag.nails.portfolio.error.WorkNotFoundException;
import gag.nails.portfolio.model.WorkConsent;
import gag.nails.portfolio.model.WorkView;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Копия согласий на публикацию фото следует за событиями модуля клиентов в их транзакции. */
@PortfolioModuleTest
class PortfolioConsentTest extends PortfolioTestSupport {

    @Test
    void withdrawalHidesClientWorksAtOnce() {
        var card = card("Мария");
        givePhotoConsent(card);
        var first = clientWork(card, "Френч");
        var second = clientWork(card, "Нюд");
        var other = example("Пример");
        assertThat(publishedIds()).containsExactly(first.id(), second.id(), other.id());

        withdrawPhotoConsent(card);

        assertThat(publishedIds()).containsExactly(other.id());
        assertThat(queries.published(ALL).total()).isEqualTo(1);
        assertThat(view(first.id()).consent()).isEqualTo(WorkConsent.MISSING);
        assertThat(queries.imageVisible(first.fileId(), Optional.empty())).isFalse();
        assertThat(consentRows(card)).isZero();
    }

    @Test
    void consentGivenPublishes() {
        var card = card("Мария");
        var work = clientWork(card, "Френч");
        assertThat(publishedIds()).isEmpty();

        givePhotoConsent(card);
        // Повторное согласие при действующем — тоже событие; копия его переносит.
        givePhotoConsent(card);

        assertThat(publishedIds()).containsExactly(work.id());
        assertThat(view(work.id()).consent()).isEqualTo(WorkConsent.GIVEN);
        assertThat(consentRows(card)).isEqualTo(1);

        withdrawPhotoConsent(card);
        givePhotoConsent(card);

        assertThat(publishedIds()).containsExactly(work.id());
    }

    @Test
    void otherConsentKindsAreIgnored() {
        var card = card("Мария");
        givePhotoConsent(card);
        var work = clientWork(card, "Френч");
        var reviews = ConsentKind.REVIEW_PUBLICATION;
        clients.recordConsent(
                card,
                new RecordConsent(
                        reviews, documents.current(reviews.document()).version(), ConsentSource.ENTERED_BY_MASTER),
                Actor.master());

        clients.withdrawConsent(card, reviews, Actor.master());

        assertThat(publishedIds()).containsExactly(work.id());
        var other = card("Анна");
        clientWork(other, "Нюд");
        clients.recordConsent(
                other,
                new RecordConsent(
                        reviews, documents.current(reviews.document()).version(), ConsentSource.ENTERED_BY_MASTER),
                Actor.master());
        assertThat(publishedIds()).containsExactly(work.id());
    }

    @Test
    void mergeMovesWorksAndConsent() {
        var removed = card("Мария");
        givePhotoConsent(removed);
        var removedWork = clientWork(removed, "Френч");
        var survivor = card("Маша");
        var survivorWork = clientWork(survivor, "Нюд");
        assertThat(publishedIds()).containsExactly(removedWork.id());

        clients.merge(removed, survivor, Actor.master());

        assertThat(queries.all(MASTER)).extracting(WorkView::clientId).containsOnly(survivor);
        assertThat(queries.all(MASTER)).extracting(WorkView::consent).containsOnly(WorkConsent.GIVEN);
        assertThat(publishedIds()).containsExactly(removedWork.id(), survivorWork.id());
        assertThat(consentRows(removed)).isZero();
        assertThat(consentRows(survivor)).isEqualTo(1);
    }

    @Test
    void mergeKeepsSurvivorConsentAndAddsNoneWhereThereWasNone() {
        var removed = card("Мария");
        var removedWork = clientWork(removed, "Френч");
        var survivor = card("Маша");
        givePhotoConsent(survivor);
        var survivorWork = clientWork(survivor, "Нюд");
        var lonely = card("Анна");
        var lonelyWork = clientWork(lonely, "Пусто");
        var lonelySurvivor = card("Аня");

        clients.merge(removed, survivor, Actor.master());
        clients.merge(lonely, lonelySurvivor, Actor.master());

        assertThat(publishedIds()).containsExactly(removedWork.id(), survivorWork.id());
        assertThat(view(lonelyWork.id()).clientId()).isEqualTo(lonelySurvivor);
        assertThat(view(lonelyWork.id()).consent()).isEqualTo(WorkConsent.MISSING);
        assertThat(consentRows(lonelySurvivor)).isZero();
    }

    @Test
    void eraseDeletesWorksAndFiles() {
        var card = card("Мария");
        givePhotoConsent(card);
        var first = clientWork(card, "Френч");
        var second = clientWork(card, "Нюд");
        var other = card("Анна");
        givePhotoConsent(other);
        var kept = clientWork(other, "Остаётся");
        var example = example("Пример");

        clients.erase(card);

        assertThat(queries.all(MASTER)).extracting(WorkView::id).containsExactly(kept.id(), example.id());
        assertThatThrownBy(() -> view(first.id())).isInstanceOf(WorkNotFoundException.class);
        assertThat(files.find(first.fileId())).isEmpty();
        assertThat(files.find(second.fileId())).isEmpty();
        assertThat(files.find(kept.fileId())).isPresent();
        assertThat(consentRows(card)).isZero();
        assertThat(consentRows(other)).isEqualTo(1);
    }

    private int consentRows(UUID clientId) {
        return jdbc.sql("select count(*) from portfolio.publication_consent where client_id = :clientId")
                .param("clientId", clientId)
                .query(Integer.class)
                .single();
    }
}
