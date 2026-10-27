package gag.nails.portfolio.internal.consent;

import gag.nails.clients.event.ConsentGiven;
import gag.nails.clients.event.ConsentWithdrawn;
import gag.nails.clients.model.ConsentKind;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Согласие на публикацию фото дано или отозвано: копия согласий меняется вместе с ним. Обычный {@code @EventListener}
 * — выполняется синхронно в транзакции модуля клиентов, как требуют {@link ConsentGiven} и {@link ConsentWithdrawn}:
 * отзыв и снятие работ клиента с публикации фиксируются вместе или не фиксируются вовсе. Остальные виды согласий
 * портфолио не касаются.
 *
 * <p>Модуль клиентов публикует эти события под блокировкой карточки, поэтому согласия одного клиента приходят по
 * одному и в порядке фиксации. Подписчик берёт только строку копии — после карточки, встречного порядка нет.
 * Повтор {@link ConsentGiven} при действующем согласии ничего не меняет.
 */
@Component
class PortfolioConsentListener {

    private final PublicationConsentRepository consents;

    PortfolioConsentListener(PublicationConsentRepository consents) {
        this.consents = consents;
    }

    @EventListener
    void on(ConsentGiven event) {
        if (event.kind() == ConsentKind.PHOTO_PUBLICATION) {
            consents.insertIfAbsent(event.clientId(), event.occurredAt());
        }
    }

    @EventListener
    void on(ConsentWithdrawn event) {
        if (event.kind() == ConsentKind.PHOTO_PUBLICATION) {
            consents.deleteByClient(event.clientId());
        }
    }
}
