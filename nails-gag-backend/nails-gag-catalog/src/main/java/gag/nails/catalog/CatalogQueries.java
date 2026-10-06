package gag.nails.catalog;

import gag.nails.catalog.error.ServiceInactiveException;
import gag.nails.catalog.error.ServiceNotFoundException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import java.util.List;
import java.util.UUID;

/** Чтение каталога. Прайс публичный: доступно всем, кроме отмеченных методов. */
public interface CatalogQueries {

    /**
     * Все услуги, включая скрытые, в порядке прайса. Нужен уровень чтения раздела «Прайс».
     *
     * @throws AccessDeniedException если у актора нет чтения раздела «Прайс»
     */
    List<ServiceView> all(Actor actor);

    /** Активные услуги в порядке прайса — для формы записи. */
    List<ServiceView> activeServices();

    /** Активные услуги с признаком показа на лендинге, в порядке прайса. */
    List<ServiceView> landingPriceList();

    /** @throws ServiceNotFoundException если услуги нет */
    ServiceView get(UUID serviceId);

    /**
     * Услуги по идентификаторам в том же порядке, что запрошены.
     *
     * @throws ServiceNotFoundException если какой-то услуги нет
     * @throws ServiceInactiveException если какая-то услуга скрыта
     */
    List<ServiceView> requireActive(List<UUID> serviceIds);
}
