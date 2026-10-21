package gag.nails.portfolio.internal.consent;

import gag.nails.clients.event.ClientMerged;
import gag.nails.portfolio.internal.work.ClientWorks;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Объединение клиентов: работы удалённой карточки переходят к оставшейся, согласие на публикацию — тоже (у
 * оставшейся оно есть, если было у любой из двух: модуль клиентов так же переносит строки согласий). Обычный
 * {@code @EventListener} — выполняется синхронно в транзакции объединения, как требует {@link ClientMerged}; события
 * отзыва согласия при объединении нет.
 *
 * <p>Обе карточки уже заблокированы модулем клиентов: запись и отзыв согласия любой из них ждут конца объединения.
 * Порядок блокировок — карточки, затем блокировка клиента и строки работ ({@link ClientWorks}), затем строки копии
 * согласий.
 */
// Имя бина задано: у модуля записей слушатель с тем же именем класса.
@Component("portfolioClientMergeListener")
class ClientMergeListener {

    private final ClientWorks works;
    private final PublicationConsentRepository consents;

    ClientMergeListener(ClientWorks works, PublicationConsentRepository consents) {
        this.works = works;
        this.consents = consents;
    }

    @EventListener
    void on(ClientMerged event) {
        works.reassign(event.removedClientId(), event.survivorClientId());
        consents.copy(event.removedClientId(), event.survivorClientId());
        consents.deleteByClient(event.removedClientId());
    }
}
