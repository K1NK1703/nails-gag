package gag.nails.catalog;

import gag.nails.catalog.error.ServiceNotFoundException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import java.util.List;
import java.util.UUID;

/**
 * Ведение каталога. Все операции — только для мастера ({@link AccessDeniedException} иначе).
 * Команды проверяются Bean Validation и правилом «длительность только у основных услуг»
 * ({@link ValidationException}).
 */
public interface CatalogService {

    /** Новая услуга становится последней в прайсе. */
    ServiceView create(CreateService command, Actor actor);

    /** @throws ServiceNotFoundException если услуги нет */
    ServiceView update(UUID serviceId, UpdateService command, Actor actor);

    /** @throws ServiceNotFoundException если услуги нет */
    void activate(UUID serviceId, Actor actor);

    /**
     * Скрытая услуга остаётся в истории записей, но недоступна для новых.
     *
     * @throws ServiceNotFoundException если услуги нет
     */
    void deactivate(UUID serviceId, Actor actor);

    /**
     * Задаёт порядок прайса: перечисленные услуги получают позиции 0, 1, 2… в порядке списка,
     * остальные следуют за ними, сохраняя прежний относительный порядок. Итог — сплошная
     * нумерация без совпадающих позиций.
     *
     * @throws ValidationException если в списке есть повторы
     * @throws ServiceNotFoundException если какой-то услуги нет
     */
    void reorder(List<UUID> orderedServiceIds, Actor actor);
}
