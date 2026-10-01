package gag.nails.catalog.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;
import java.util.UUID;

/** Услуга скрыта из каталога и недоступна для новых записей. */
public final class ServiceInactiveException extends UserFacingException {

    public ServiceInactiveException(UUID serviceId, String title) {
        super("catalog.service_inactive", Map.of("serviceId", serviceId, "title", title));
    }
}
