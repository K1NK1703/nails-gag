package gag.nails.portfolio.internal.consent;

import gag.nails.clients.event.ClientErased;
import gag.nails.portfolio.internal.work.ClientWorks;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Обезличивание клиента: его работы удаляются вместе с файлами (файлы на диске — после фиксации), строка согласия на
 * публикацию — тоже. Обычный {@code @EventListener} — выполняется синхронно в транзакции обезличивания, как требует
 * {@link ClientErased}; события отзыва согласия при обезличивании нет.
 *
 * <p>Карточка уже заблокирована модулем клиентов. Порядок блокировок — карточка, затем блокировка клиента и строки
 * работ ({@link ClientWorks}), затем строки файлов и копии согласий. Добавление или правка работы с записью этого
 * клиента, начатые одновременно, либо уже зафиксированы, и работа удаляется здесь, либо дождутся и откажут: клиент
 * обезличен.
 */
@Component
class PortfolioClientEraseListener {

    private final ClientWorks works;
    private final PublicationConsentRepository consents;

    PortfolioClientEraseListener(ClientWorks works, PublicationConsentRepository consents) {
        this.works = works;
        this.consents = consents;
    }

    @EventListener
    void on(ClientErased event) {
        works.deleteAll(event.clientId());
        consents.deleteByClient(event.clientId());
    }
}
