package gag.nails.catalog.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;
import java.util.UUID;

/** Услуги с таким идентификатором нет в каталоге. */
public final class ServiceNotFoundException extends UserFacingException {

    public ServiceNotFoundException(UUID serviceId) {
        super("catalog.service_not_found", Map.of("serviceId", serviceId));
    }
}
