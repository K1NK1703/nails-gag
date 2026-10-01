package gag.nails.catalog.internal;

import gag.nails.catalog.CatalogService;
import gag.nails.catalog.CreateService;
import gag.nails.catalog.ServiceKind;
import gag.nails.catalog.ServiceView;
import gag.nails.catalog.UpdateService;
import gag.nails.catalog.error.ServiceNotFoundException;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.ValidationException;
import gag.nails.common.validation.CommandValidator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
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
        actor.requireMaster();
        validator.requireValid(command);
        requireDurationMatchesKind(command.kind(), command.durationMinutes());
        var entity = new ServiceEntity(command, (int) services.count());
        return services.save(entity).toView();
    }

    @Override
    public ServiceView update(UUID serviceId, UpdateService command, Actor actor) {
        actor.requireMaster();
        validator.requireValid(command);
        var entity = require(serviceId);
        requireDurationMatchesKind(entity.kind(), command.durationMinutes());
        entity.apply(command);
        return entity.toView();
    }

    @Override
    public void activate(UUID serviceId, Actor actor) {
        actor.requireMaster();
        require(serviceId).setActive(true);
    }

    @Override
    public void deactivate(UUID serviceId, Actor actor) {
        actor.requireMaster();
        require(serviceId).setActive(false);
    }

    @Override
    public void reorder(List<UUID> orderedServiceIds, Actor actor) {
        actor.requireMaster();
        var byId = services.findAllByIdIn(orderedServiceIds).stream()
                .collect(Collectors.toMap(ServiceEntity::id, Function.identity()));
        for (int position = 0; position < orderedServiceIds.size(); position++) {
            var id = orderedServiceIds.get(position);
            var entity = byId.get(id);
            if (entity == null) {
                throw new ServiceNotFoundException(id);
            }
            entity.setSortOrder(position);
        }
    }

    private ServiceEntity require(UUID serviceId) {
        return services.findById(serviceId).orElseThrow(() -> new ServiceNotFoundException(serviceId));
    }

    /** Длительность есть у основных услуг и только у них; дополнения слот не занимают. */
    private static void requireDurationMatchesKind(ServiceKind kind, @Nullable Integer durationMinutes) {
        if (kind == ServiceKind.MAIN && durationMinutes == null) {
            throw new ValidationException("durationMinutes", "required for a main service");
        }
        if (kind == ServiceKind.ADDON && durationMinutes != null) {
            throw new ValidationException("durationMinutes", "not allowed for an add-on");
        }
    }
}
