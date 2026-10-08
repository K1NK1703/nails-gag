package gag.nails.catalog.internal;

import gag.nails.catalog.CatalogQueries;
import gag.nails.catalog.error.ServiceInactiveException;
import gag.nails.catalog.error.ServiceNotFoundException;
import gag.nails.catalog.model.ServiceView;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
    public List<ServiceView> all(Actor actor) {
        actor.requireRead(Section.CATALOG);
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
    public ServiceView get(UUID serviceId) {
        return services.findById(serviceId)
                .map(ServiceEntity::toView)
                .orElseThrow(() -> new ServiceNotFoundException(serviceId));
    }

    @Override
    public List<ServiceView> requireActive(List<UUID> serviceIds) {
        var found = services.findByIds(serviceIds);
        return serviceIds.stream().map(id -> activeView(found.get(id), id)).toList();
    }

    private static ServiceView activeView(@Nullable ServiceEntity entity, UUID id) {
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
