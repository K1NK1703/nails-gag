package gag.nails.catalog;

import gag.nails.catalog.error.ServiceInactiveException;
import gag.nails.catalog.error.ServiceNotFoundException;
import java.util.List;
import java.util.UUID;

/** Чтение каталога. Доступно без ограничений по ролям: прайс публичный. */
public interface CatalogQueries {

    /** Все услуги, включая скрытые, в порядке прайса — для админки. */
    List<ServiceView> all();

    /** Активные услуги в порядке прайса — для формы записи. */
    List<ServiceView> activeServices();

    /** Активные услуги с признаком показа на лендинге, в порядке прайса. */
    List<ServiceView> landingPriceList();

    /** @throws ServiceNotFoundException если услуги нет */
    ServiceView byId(UUID serviceId);

    /**
     * Услуги по идентификаторам в том же порядке, что запрошены.
     *
     * @throws ServiceNotFoundException если какой-то услуги нет
     * @throws ServiceInactiveException если какая-то услуга скрыта
     */
    List<ServiceView> requireActive(List<UUID> serviceIds);
}
