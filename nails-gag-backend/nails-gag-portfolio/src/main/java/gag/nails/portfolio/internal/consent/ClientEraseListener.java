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
 * работ ({@link ClientWorks}), затем строки файлов и копии согласий. Добавление работы этого клиента, начатое
 * одновременно, либо уже зафиксировано и удаляется здесь, либо дождётся и откажет: клиент обезличен.
 */
// Имя бина задано: у модуля записей слушатель с тем же именем класса.
@Component("portfolioClientEraseListener")
class ClientEraseListener {

    private final ClientWorks works;
    private final PublicationConsentRepository consents;

    ClientEraseListener(ClientWorks works, PublicationConsentRepository consents) {
        this.works = works;
        this.consents = consents;
    }

    @EventListener
    void on(ClientErased event) {
        works.deleteAll(event.clientId());
        consents.deleteByClient(event.clientId());
    }
}
