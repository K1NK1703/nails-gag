package gag.nails.clients.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;
import java.util.UUID;

/** Клиента с таким идентификатором нет. */
public final class ClientNotFoundException extends UserFacingException {

    public ClientNotFoundException(UUID clientId) {
        super("clients.client_not_found", Map.of("clientId", clientId));
    }
}
