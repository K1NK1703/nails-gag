package gag.nails.portfolio.internal.work;

import gag.nails.portfolio.model.WorkConsent;
import gag.nails.portfolio.model.WorkView;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Работа вместе со строкой согласия её клиента из того же запроса.
 *
 * @param consentClientId клиент из строки согласия; пусто — строки нет (или работа не клиентская)
 */
record WorkRow(WorkEntity work, @Nullable UUID consentClientId) {

    /** Правило публичности; в запросах — {@link WorkRepository#PUBLISHED}, они должны совпадать. */
    boolean published() {
        return work.visible() && consent() != WorkConsent.MISSING;
    }

    WorkConsent consent() {
        return switch (work.source()) {
            case CLIENT -> consentClientId != null ? WorkConsent.GIVEN : WorkConsent.MISSING;
            case CLIENT_OUTSIDE_SITE -> WorkConsent.OUTSIDE_SITE;
            case EXAMPLE -> WorkConsent.NOT_NEEDED;
        };
    }

    WorkView toView() {
        return new WorkView(
                work.id(),
                work.fileId(),
                work.alt(),
                work.caption(),
                work.position(),
                work.visible(),
                work.source(),
                work.bookingId(),
                work.clientId(),
                work.width(),
                work.height(),
                published(),
                consent(),
                work.createdAt());
    }
}
