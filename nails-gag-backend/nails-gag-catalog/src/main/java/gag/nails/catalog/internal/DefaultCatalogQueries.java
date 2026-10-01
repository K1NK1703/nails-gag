package gag.nails.catalog.internal;

import gag.nails.catalog.CatalogQueries;
import gag.nails.catalog.ServiceView;
import gag.nails.catalog.error.ServiceInactiveException;
import gag.nails.catalog.error.ServiceNotFoundException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultCatalogQueries implements CatalogQueries {

    private final ServiceRepository services;

    DefaultCatalogQueries(ServiceRepository services) {
        this.services = services;
    }

    @Override
    public List<ServiceView> all() {
        return views(services.findAllByOrderBySortOrderAscTitleAsc());
    }

    @Override
    public List<ServiceView> activeServices() {
        return views(services.findAllByActiveTrueOrderBySortOrderAscTitleAsc());
    }

    @Override
    public List<ServiceView> landingPriceList() {
        return views(services.findAllByActiveTrueAndShowOnLandingTrueOrderBySortOrderAscTitleAsc());
    }

    @Override
    public ServiceView byId(UUID serviceId) {
        return services.findById(serviceId)
                .map(ServiceEntity::toView)
                .orElseThrow(() -> new ServiceNotFoundException(serviceId));
    }

    @Override
    public List<ServiceView> requireActive(List<UUID> serviceIds) {
        var found = services.findAllByIdIn(serviceIds).stream()
                .collect(Collectors.toMap(ServiceEntity::id, Function.identity()));
        return serviceIds.stream().map(id -> requireActive(found.get(id), id)).toList();
    }

    private static ServiceView requireActive(ServiceEntity entity, UUID id) {
        if (entity == null) {
            throw new ServiceNotFoundException(id);
        }
        if (!entity.active()) {
            throw new ServiceInactiveException(entity.id(), entity.title());
        }
        return entity.toView();
    }

    private static List<ServiceView> views(List<ServiceEntity> entities) {
        return entities.stream().map(ServiceEntity::toView).toList();
    }
}
