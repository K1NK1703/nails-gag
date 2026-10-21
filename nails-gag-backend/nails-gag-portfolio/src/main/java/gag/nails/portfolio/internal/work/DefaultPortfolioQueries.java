package gag.nails.portfolio.internal.work;

import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.paging.Paged;
import gag.nails.common.paging.Paging;
import gag.nails.files.FileStorage;
import gag.nails.files.model.FilePurpose;
import gag.nails.portfolio.PortfolioQueries;
import gag.nails.portfolio.error.WorkNotFoundException;
import gag.nails.portfolio.model.PublicWork;
import gag.nails.portfolio.model.WorkView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultPortfolioQueries implements PortfolioQueries {

    private final WorkRepository works;
    private final FileStorage files;

    DefaultPortfolioQueries(WorkRepository works, FileStorage files) {
        this.works = works;
        this.files = files;
    }

    /**
     * Страница и {@code total} — два запроса; {@code REPEATABLE READ} даёт им один снимок базы, чтобы отзыв согласия
     * между ними не рассогласовал страницу и число. Внутри чужой транзакции — её снимок.
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Paged<PublicWork> published(Paging paging) {
        var page = works.findPublished(PageRequest.of(paging.page(), paging.size()));
        return Paged.of(page.getContent(), paging, page.getTotalElements()).map(WorkRow::toPublic);
    }

    @Override
    public List<WorkView> all(Actor actor) {
        actor.requireRead(Section.PORTFOLIO);
        return works.findAllRows().stream().map(WorkRow::toView).toList();
    }

    @Override
    public WorkView get(UUID workId, Actor actor) {
        actor.requireRead(Section.PORTFOLIO);
        return works.findRowById(workId).map(WorkRow::toView).orElseThrow(() -> new WorkNotFoundException(workId));
    }

    @Override
    public boolean imageVisible(UUID fileId, Optional<Actor> actor) {
        if (actor.filter(staff -> staff.canRead(Section.PORTFOLIO)).isPresent()) {
            return files.find(fileId)
                    .filter(file -> file.purpose() == FilePurpose.PORTFOLIO)
                    .isPresent();
        }
        return imagePublished(fileId);
    }

    @Override
    public boolean imagePublished(UUID fileId) {
        return works.countPublishedByFileId(fileId) > 0;
    }
}
