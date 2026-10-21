package gag.nails.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.paging.Paging;
import gag.nails.files.model.FilePurpose;
import gag.nails.portfolio.error.WorkNotFoundException;
import gag.nails.portfolio.model.PublicWork;
import gag.nails.portfolio.model.WorkConsent;
import gag.nails.portfolio.model.WorkSource;
import gag.nails.portfolio.model.WorkView;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@PortfolioModuleTest
class PortfolioQueriesTest extends PortfolioTestSupport {

    @ParameterizedTest(name = "{0}, согласие {1}, видима {2} → опубликована {3}")
    @CsvSource({
        "CLIENT, true, true, true",
        "CLIENT, false, true, false",
        "CLIENT, true, false, false",
        "CLIENT, false, false, false",
        "CLIENT_OUTSIDE_SITE, false, true, true",
        "CLIENT_OUTSIDE_SITE, false, false, false",
        "EXAMPLE, false, true, true",
        "EXAMPLE, false, false, false"
    })
    void publishedFollowsSourceVisibilityAndConsent(
            WorkSource source, boolean consent, boolean visible, boolean published) {
        var work = switch (source) {
            case CLIENT -> {
                var card = card("Мария");
                if (consent) {
                    givePhotoConsent(card);
                }
                yield clientWork(card, "Френч");
            }
            case CLIENT_OUTSIDE_SITE -> outsideSite("Френч");
            case EXAMPLE -> example("Френч");
        };
        if (!visible) {
            portfolio.hide(work.id(), MASTER);
        }

        assertThat(publishedIds().contains(work.id())).isEqualTo(published);
        assertThat(queries.published(ALL).total()).isEqualTo(published ? 1 : 0);
        assertThat(view(work.id()).published()).isEqualTo(published);
        assertThat(queries.imagePublished(work.fileId())).isEqualTo(published);
        assertThat(queries.imageVisible(work.fileId(), Optional.empty())).isEqualTo(published);
    }

    @Test
    void publishedIsPagedInPositionOrder() {
        var first = example("Первая");
        var hidden = example("Скрытая");
        var second = example("Вторая");
        var third = example("Третья");
        var fourth = example("Четвёртая");
        portfolio.hide(hidden.id(), MASTER);

        var page = queries.published(new Paging(0, 3));
        var last = queries.published(new Paging(1, 3));

        assertThat(page.items()).extracting(PublicWork::id).containsExactly(first.id(), second.id(), third.id());
        assertThat(page.total()).isEqualTo(4);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(3);
        assertThat(last.items()).extracting(PublicWork::id).containsExactly(fourth.id());
        assertThat(last.total()).isEqualTo(4);
        assertThat(page.items().getFirst())
                .isEqualTo(new PublicWork(first.id(), first.fileId(), "Первая", null, 640, 480));
    }

    @Test
    void allShowsHiddenAndConsentStatus() {
        var withConsent = card("Мария");
        givePhotoConsent(withConsent);
        var given = clientWork(withConsent, "С согласием");
        var missing = clientWork(card("Анна"), "Без согласия");
        var outside = outsideSite("Вне сайта");
        var example = example("Пример");
        portfolio.hide(example.id(), MASTER);

        var all = queries.all(MASTER);

        assertThat(all).extracting(WorkView::id).containsExactly(given.id(), missing.id(), outside.id(), example.id());
        assertThat(all)
                .extracting(WorkView::consent)
                .containsExactly(
                        WorkConsent.GIVEN, WorkConsent.MISSING, WorkConsent.OUTSIDE_SITE, WorkConsent.NOT_NEEDED);
        assertThat(all).extracting(WorkView::published).containsExactly(true, false, true, false);
        assertThat(all).extracting(WorkView::visible).containsExactly(true, true, true, false);
        assertThat(queries.all(VIEWER)).isEqualTo(all);
        assertThat(queries.get(missing.id(), VIEWER)).isEqualTo(all.get(1));
    }

    @Test
    void readNeedsPortfolioRights() {
        var work = example("Пример");
        var client = Actor.client(card("Мария"));
        var noPortfolio = Actor.of(UUID.randomUUID(), null, Map.of());

        assertThatThrownBy(() -> queries.all(client)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queries.all(noPortfolio)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queries.get(work.id(), client)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queries.get(UUID.randomUUID(), MASTER))
                .isInstanceOfSatisfying(
                        WorkNotFoundException.class, e -> assertThat(e.code()).isEqualTo("portfolio.work_not_found"));
    }

    @Test
    void imageVisibleForPublishedOrStaff() {
        var hidden = example("Скрытая");
        portfolio.hide(hidden.id(), MASTER);
        var published = example("Видимая");
        var orphan = upload();
        var reference = upload(FilePurpose.REFERENCE, MASTER);
        var client = Optional.of(Actor.client(card("Мария")));

        assertThat(queries.imageVisible(published.fileId(), Optional.empty())).isTrue();
        assertThat(queries.imageVisible(published.fileId(), client)).isTrue();
        assertThat(queries.imageVisible(hidden.fileId(), Optional.empty())).isFalse();
        assertThat(queries.imageVisible(hidden.fileId(), client)).isFalse();
        assertThat(queries.imageVisible(hidden.fileId(), Optional.of(VIEWER))).isTrue();
        assertThat(queries.imagePublished(hidden.fileId())).isFalse();
        // Загруженное фото мастер видит до сохранения работы; чужим оно не видно.
        assertThat(queries.imageVisible(orphan, Optional.of(VIEWER))).isTrue();
        assertThat(queries.imageVisible(orphan, Optional.empty())).isFalse();
        // Референсы к записям — не файлы портфолио.
        assertThat(queries.imageVisible(reference, Optional.of(VIEWER))).isFalse();
        assertThat(queries.imageVisible(UUID.randomUUID(), Optional.of(VIEWER))).isFalse();
    }
}
