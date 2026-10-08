package gag.nails.catalog.internal;

import gag.nails.catalog.CatalogService;
import gag.nails.catalog.command.CreateService;
import gag.nails.catalog.command.UpdateService;
import gag.nails.catalog.error.ServiceNotFoundException;
import gag.nails.catalog.model.ServiceView;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.common.error.ValidationException;
import gag.nails.common.validation.CommandValidator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultCatalogService implements CatalogService {

    private final ServiceRepository services;
    private final CommandValidator validator;

    DefaultCatalogService(ServiceRepository services, CommandValidator validator) {
        this.services = services;
        this.validator = validator;
    }

    @Override
    public ServiceView create(CreateService command, Actor actor) {
        actor.requireWrite(Section.CATALOG);
        validator.requireValid(command);
        var entity = new ServiceEntity(command, nextSortOrder());
        return services.save(entity).toView();
    }

    @Override
    public ServiceView update(UUID serviceId, UpdateService command, Actor actor) {
        actor.requireWrite(Section.CATALOG);
        validator.requireValid(command);
        var entity = require(serviceId);
        entity.apply(command);
        return entity.toView();
    }

    @Override
    public void activate(UUID serviceId, Actor actor) {
        actor.requireWrite(Section.CATALOG);
        require(serviceId).setActive(true);
    }

    @Override
    public void deactivate(UUID serviceId, Actor actor) {
        actor.requireWrite(Section.CATALOG);
        require(serviceId).setActive(false);
    }

    @Override
    public void reorder(List<UUID> orderedServiceIds, Actor actor) {
        actor.requireWrite(Section.CATALOG);
        if (Set.copyOf(orderedServiceIds).size() != orderedServiceIds.size()) {
            throw new ValidationException("orderedServiceIds", "must not contain duplicates");
        }
        var listed = services.findByIds(orderedServiceIds);
        var all = services.findAllByOrderBySortOrderAscTitleAsc();
        var position = 0;
        for (var id : orderedServiceIds) {
            var entity = listed.get(id);
            if (entity == null) {
                throw new ServiceNotFoundException(id);
            }
            entity.setSortOrder(position++);
        }
        for (var entity : all) {
            if (!listed.containsKey(entity.id())) {
                entity.setSortOrder(position++);
            }
        }
    }

    private ServiceEntity require(UUID serviceId) {
        return services.findById(serviceId).orElseThrow(() -> new ServiceNotFoundException(serviceId));
    }

    private int nextSortOrder() {
        return services.findFirstByOrderBySortOrderDesc()
                .map(last -> last.sortOrder() + 1)
                .orElse(0);
    }
}
